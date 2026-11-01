package com.ticketflow.bookingservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The circuit breaker in action, with a REAL HTTP fake of event-service whose behaviour the tests
 * switch: healthy, down (500) or "unknown event" (404). The fake counts the requests it receives,
 * which is the proof that an OPEN circuit really stops calling it.
 * <p>
 * Each test gets a fresh application context (and so a fresh, CLOSED circuit breaker).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resiliencetest;DB_CLOSE_DELAY=-1",
        "spring.cloud.openfeign.circuitbreaker.enabled=true",
        "spring.cloud.openfeign.circuitbreaker.group.enabled=true",
        "spring.cloud.openfeign.client.config.default.read-timeout=500",
        // 1 s instead of 10 s, so that the test does not wait
        "ticketflow.resilience.event-service.wait-in-open-state=1s"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ResilienceTest {

    private enum Mode { UP, DOWN, UNKNOWN_EVENT }

    private static volatile Mode mode = Mode.UP;
    private static final AtomicInteger REQUESTS_RECEIVED = new AtomicInteger();
    private static final HttpServer EVENT_SERVICE = startFakeEventService();

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void eventServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.clients.event-service.url",
                () -> "http://localhost:" + EVENT_SERVICE.getAddress().getPort());
    }

    @BeforeEach
    void healthyEventService() {
        mode = Mode.UP;
        REQUESTS_RECEIVED.set(0);
    }

    @AfterAll
    static void stopFakeEventService() {
        EVENT_SERVICE.stop(0);
    }

    // ---- the fake event-service ----

    private static HttpServer startFakeEventService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/api/events/", ResilienceTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS_RECEIVED.incrementAndGet();
        String id = exchange.getRequestURI().getPath().substring("/api/events/".length());
        switch (mode) {
            case UP -> reply(exchange, 200, event(id));
            case DOWN -> reply(exchange, 500, "{\"title\":\"Internal Server Error\",\"status\":500}");
            case UNKNOWN_EVENT -> reply(exchange, 404, "{\"title\":\"Not Found\",\"status\":404}");
        }
    }

    private static String event(String id) {
        return """
                {"id":%s,"title":"Jazz night","description":"Live","venue":"Casablanca",
                 "startsAt":"%s","price":150.00,"capacity":1000,"status":"PUBLISHED","createdAt":"%s"}
                """.formatted(id, Instant.now().plus(30, ChronoUnit.DAYS), Instant.now());
    }

    private static void reply(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    // ---- helper ----

    private int book(long eventId) throws Exception {
        String body = "{\"eventId\":" + eventId + ",\"customerId\":42,\"quantity\":1}";
        return mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    // ---- the scenarios ----

    @Test
    void anUnknownEventIsABusinessAnswerAndNeverOpensTheCircuit() throws Exception {
        mode = Mode.UNKNOWN_EVENT;
        for (int i = 0; i < 20; i++) {
            assertThat(book(1)).as("booking %d of an unknown event", i).isEqualTo(404);
        }
        assertThat(REQUESTS_RECEIVED.get()).as("every call reached event-service").isEqualTo(20);

        mode = Mode.UP; // a healthy event: the circuit is still CLOSED, so it works at once
        assertThat(book(2)).isEqualTo(201);
    }

    @Test
    void anOutageOpensTheCircuitAndTheServiceRecoversAfterwards() throws Exception {
        // 1. event-service is down: every call fails, and each one really reaches it (circuit CLOSED)
        mode = Mode.DOWN;
        for (int i = 0; i < 5; i++) {
            assertThat(book(3)).as("failure %d", i).isEqualTo(503);
        }
        assertThat(REQUESTS_RECEIVED.get()).isEqualTo(5);

        // 2. 5 calls with 100 % of failures: the circuit is OPEN, event-service is no longer called
        for (int i = 0; i < 5; i++) {
            assertThat(book(3)).as("call %d with an open circuit", i).isEqualTo(503);
        }
        assertThat(REQUESTS_RECEIVED.get()).as("no request while the circuit is open").isEqualTo(5);

        // 3. event-service is back; after the wait (1 s) the circuit lets test calls through (HALF_OPEN)
        mode = Mode.UP;
        Thread.sleep(1300);
        for (int i = 0; i < 3; i++) {
            assertThat(book(4)).as("test call %d", i).isEqualTo(201);
        }

        // 4. the test calls succeeded: the circuit is CLOSED again, everything flows normally
        for (int i = 0; i < 5; i++) {
            assertThat(book(4)).as("booking %d after recovery", i).isEqualTo(201);
        }
    }
}
