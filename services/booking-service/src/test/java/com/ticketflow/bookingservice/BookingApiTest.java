package com.ticketflow.bookingservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventView;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * event-service is replaced by a mock (no Eureka, no network): what is tested here is the booking
 * logic and the seat counting. The real HTTP exchange is tested in EventClientContractTest.
 * <p>
 * The database is a REAL PostgreSQL started in Docker (Testcontainers), not H2: the seat counting
 * relies on row locks and on concurrent inserts, which PostgreSQL does not handle exactly like H2.
 * Docker must be running to execute this class.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class BookingApiTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start(); // stopped automatically by Testcontainers when the JVM exits
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /** Each test uses its own event id: the database is shared by the whole class. */
    private static final AtomicLong EVENT_IDS = new AtomicLong(1000);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EventClient events;

    // ---- helpers ----

    private Long eventWith(int capacity, String price, String status, Instant startsAt) {
        Long id = EVENT_IDS.incrementAndGet();
        when(events.get(id)).thenReturn(new EventView(id, "Jazz night", startsAt, new BigDecimal(price), capacity, status));
        return id;
    }

    private Long publishedEvent(int capacity) {
        return eventWith(capacity, "150.00", "PUBLISHED", Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private static FeignException feignError(int status) {
        FeignException e = mock(FeignException.class);
        when(e.status()).thenReturn(status);
        return e;
    }

    private static String body(Long eventId, long customerId, int quantity) {
        return "{\"eventId\":" + eventId + ",\"customerId\":" + customerId + ",\"quantity\":" + quantity + "}";
    }

    private int book(Long eventId, long customerId, int quantity) throws Exception {
        return mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body(eventId, customerId, quantity)))
                .andReturn().getResponse().getStatus();
    }

    private Integer bookedId(Long eventId, long customerId, int quantity) throws Exception {
        String json = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body(eventId, customerId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(json, "$.id");
        return id;
    }

    // ---- booking ----

    @Test
    void booksSeatsAndSnapshotsThePrice() throws Exception {
        Long event = publishedEvent(10);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body(event, 42, 2)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.customerId").value(42))
                .andExpect(jsonPath("$.eventTitle").value("Jazz night"))
                .andExpect(jsonPath("$.unitPrice").value(150.0))
                .andExpect(jsonPath("$.totalPrice").value(300.0));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        Long event = publishedEvent(10);

        assertThat(book(event, 42, 0)).isEqualTo(400);
        assertThat(book(event, 42, 11)).isEqualTo(400);
        assertThat(book(event, 0, 1)).isEqualTo(400); // customerId must be positive
        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":42,\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":" + event + ",\"quantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answers404WhenTheEventDoesNotExist() throws Exception {
        FeignException notFound = feignError(404);
        when(events.get(anyLong())).thenThrow(notFound);

        assertThat(book(987654L, 42, 1)).isEqualTo(404);
    }

    @Test
    void refusesAnEventThatIsNotOpenOrAlreadyStarted() throws Exception {
        Long closed = eventWith(10, "10.00", "CLOSED", Instant.now().plus(5, ChronoUnit.DAYS));
        Long draft = eventWith(10, "10.00", "DRAFT", Instant.now().plus(5, ChronoUnit.DAYS));
        Long past = eventWith(10, "10.00", "PUBLISHED", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(book(closed, 42, 1)).isEqualTo(409);
        assertThat(book(draft, 42, 1)).isEqualTo(409);
        assertThat(book(past, 42, 1)).isEqualTo(409);
    }

    @Test
    void answers503WhenEventServiceIsDown() throws Exception {
        Long unreachable = publishedEvent(10);
        FeignException noConnection = feignError(-1);
        when(events.get(unreachable)).thenThrow(noConnection);
        Long failing = publishedEvent(10);
        FeignException serverError = feignError(500);
        when(events.get(failing)).thenThrow(serverError);

        assertThat(book(unreachable, 42, 1)).isEqualTo(503);
        assertThat(book(failing, 42, 1)).isEqualTo(503);
    }

    // ---- seats ----

    @Test
    void neverSellsMoreSeatsThanTheCapacity() throws Exception {
        Long event = publishedEvent(3);

        assertThat(book(event, 42, 2)).isEqualTo(201);
        assertThat(book(event, 42, 2)).isEqualTo(409); // only one seat left
        assertThat(book(event, 42, 1)).isEqualTo(201);
        assertThat(book(event, 42, 1)).isEqualTo(409); // sold out
    }

    @Test
    void cancellingGivesTheSeatsBack() throws Exception {
        Long event = publishedEvent(2);
        Integer id = bookedId(event, 42, 2);
        assertThat(book(event, 42, 1)).isEqualTo(409);

        mockMvc.perform(post("/api/bookings/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(book(event, 42, 2)).isEqualTo(201);
        // cancelling twice must not release the seats twice
        mockMvc.perform(post("/api/bookings/{id}/cancel", id)).andExpect(status().isConflict());
        assertThat(book(event, 42, 1)).isEqualTo(409);
    }

    /**
     * The dangerous moment is the FIRST booking of an event, when several requests create its seat
     * counter at the same time (a bug there once reset the counter and sold 6 seats out of 3, in about
     * 1 round out of 15). One round proves little, so the scenario is repeated on fresh events.
     */
    @Test
    void concurrentBookingsOfTheLastSeatsCannotOversell() throws Exception {
        int capacity = 3;
        int attempts = 8;
        int rounds = 40;

        for (int round = 0; round < rounds; round++) {
            Long event = publishedEvent(capacity);

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                long customer = 100 + i;
                Callable<Integer> attempt = () -> {
                    start.await();
                    return book(event, customer, 1);
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();

            int created = 0;
            for (Future<Integer> result : results) {
                int code = result.get();
                assertThat(code).isIn(201, 409);
                if (code == 201) {
                    created++;
                }
            }
            pool.shutdown();
            assertThat(created).as("seats sold in round %d", round).isEqualTo(capacity);
        }
    }

    // ---- reading ----

    @Test
    void readsABookingAndAnUnknownOneIs404() throws Exception {
        Long event = publishedEvent(10);
        Integer id = bookedId(event, 42, 1);

        mockMvc.perform(get("/api/bookings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(event));
        mockMvc.perform(get("/api/bookings/{id}", 987654)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/bookings/{id}/cancel", 987654)).andExpect(status().isNotFound());
    }

    @Test
    void listsAllBookingsOrOnlyThoseOfOneCustomer() throws Exception {
        Long event = publishedEvent(10);
        long customer = 7000 + EVENT_IDS.incrementAndGet(); // unique: the database is shared by the class
        bookedId(event, customer, 1);
        bookedId(event, customer, 2);
        bookedId(event, customer + 1, 1);

        mockMvc.perform(get("/api/bookings").param("customerId", String.valueOf(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isOk());
    }
}
