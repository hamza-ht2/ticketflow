package com.ticketflow.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The simulated provider decides from the card token, so every case can be produced on demand. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiTest {

    /** Each test uses its own booking id: the database is shared by the whole class. */
    private static final AtomicLong BOOKING_IDS = new AtomicLong(100);

    @Autowired
    MockMvc mockMvc;

    // ---- helpers ----

    private static String body(long bookingId, String amount, String token) {
        String card = token == null ? "" : ",\"cardToken\":\"" + token + "\"";
        return "{\"bookingId\":" + bookingId + ",\"amount\":" + amount + card + "}";
    }

    private MvcResult charge(long bookingId, String amount, String token, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(bookingId, amount, token)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private static long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    // ---- charge ----

    @Test
    void aSuccessfulPaymentIsCreatedWithAProviderReference() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(booking, "150.00", null)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.bookingId").value(booking))
                .andExpect(jsonPath("$.providerReference").isNotEmpty());
    }

    @Test
    void aDeclinedCardIsANormalResultNotAnHttpError() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(booking, "150.00", "tok_declined")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));
    }

    @Test
    void insufficientFundsAndUnknownCardsAreRefused() throws Exception {
        long a = BOOKING_IDS.incrementAndGet();
        long b = BOOKING_IDS.incrementAndGet();
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(a, "10", "tok_insufficient_funds")))
                .andExpect(jsonPath("$.failureReason").value("insufficient_funds"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(b, "10", "tok_whatever")))
                .andExpect(jsonPath("$.failureReason").value("unknown_card"));
    }

    @Test
    void theSlowCardSucceedsAfterADelay() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        long start = System.nanoTime();
        charge(booking, "20", "tok_slow", 201);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(150);
    }

    // ---- idempotence ----

    @Test
    void sendingTheSameRequestAgainReturnsTheSamePaymentWithoutChargingTwice() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        MvcResult first = charge(booking, "99.90", null, 201);
        MvcResult second = charge(booking, "99.90", null, 200); // 200: already existed
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat((String) JsonPath.read(second.getResponse().getContentAsString(), "$.providerReference"))
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.providerReference"));
        mockMvc.perform(get("/api/payments").param("bookingId", String.valueOf(booking)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theSameBookingWithAnotherAmountIsRejected() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        charge(booking, "50.00", null, 201);
        charge(booking, "60.00", null, 409);
    }

    @Test
    void afterAFailureTheCustomerCanRetryOnTheSamePayment() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        MvcResult failed = charge(booking, "80.00", "tok_declined", 201);
        MvcResult retried = charge(booking, "80.00", "tok_success", 200);
        assertThat(idOf(retried)).isEqualTo(idOf(failed));
        assertThat((String) JsonPath.read(retried.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void anUnreachableProviderGives503AndLeavesThePaymentRetryable() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        charge(booking, "30.00", "tok_error", 503);
        mockMvc.perform(get("/api/payments").param("bookingId", String.valueOf(booking)))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason").value("provider_unavailable"));
        charge(booking, "30.00", "tok_success", 200); // the provider is back
    }

    // ---- refund ----

    @Test
    void aSucceededPaymentCanBeRefundedAndRefundingAgainChangesNothing() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        long id = idOf(charge(booking, "45.00", null, 201));
        mockMvc.perform(post("/api/payments/" + id + "/refund"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        mockMvc.perform(post("/api/payments/" + id + "/refund"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void aFailedPaymentCannotBeRefunded() throws Exception {
        long booking = BOOKING_IDS.incrementAndGet();
        long id = idOf(charge(booking, "45.00", "tok_declined", 201));
        mockMvc.perform(post("/api/payments/" + id + "/refund")).andExpect(status().isConflict());
    }

    // ---- validation and lookup ----

    @Test
    void invalidRequestsAreRejectedWith400() throws Exception {
        charge(BOOKING_IDS.incrementAndGet(), "0", null, 400);
        charge(BOOKING_IDS.incrementAndGet(), "-5", null, 400);
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownPaymentIs404() throws Exception {
        mockMvc.perform(get("/api/payments/987654")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/payments/987654/refund")).andExpect(status().isNotFound());
    }
}
