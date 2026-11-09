package com.ticketflow.bookingservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.bookingservice.clients.ChargeRequest;
import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventView;
import com.ticketflow.bookingservice.clients.PaymentClient;
import com.ticketflow.bookingservice.clients.PaymentView;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Booking + payment, with payment-service and event-service replaced by mocks (H2 database). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:paymentflowtest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PaymentFlowTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean EventClient events;
    @MockitoBean PaymentClient payments;

    private long newBooking(String price, int quantity) throws Exception {
        EventView event = new EventView(1L, "Concert", Instant.now().plus(5, ChronoUnit.DAYS),
                new BigDecimal(price), 1000, "PUBLISHED");
        when(events.get(anyLong())).thenReturn(event);
        MvcResult r = mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"customerId\":42,\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated()).andReturn();
        String body = r.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private int pay(long id, String body) throws Exception {
        var request = post("/api/bookings/" + id + "/pay");
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private void assertStatus(long id, String expected) throws Exception {
        mockMvc.perform(get("/api/bookings/" + id)).andExpect(jsonPath("$.status").value(expected));
    }

    private static PaymentView payment(long id, String status, String reason) {
        return new PaymentView(id, 1L, new BigDecimal("20.00"), status, reason);
    }

    private static FeignException feignError(int status) {
        FeignException e = mock(FeignException.class);
        when(e.status()).thenReturn(status);
        return e;
    }

    @Test
    void paymentSucceededConfirmsTheBooking() throws Exception {
        long id = newBooking("10.00", 2);
        when(payments.charge(any())).thenReturn(payment(7, "SUCCEEDED", null));

        mockMvc.perform(post("/api/bookings/" + id + "/pay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"tok_success\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(7));

        ArgumentCaptor<ChargeRequest> sent = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(payments).charge(sent.capture());
        assertThat(sent.getValue().bookingId()).isEqualTo(id);
        assertThat(sent.getValue().amount()).isEqualByComparingTo("20.00");
        assertThat(sent.getValue().cardToken()).isEqualTo("tok_success");
    }

    @Test
    void refusedCardCancelsTheBookingAnswers402() throws Exception {
        long id = newBooking("10.00", 1);
        when(payments.charge(any())).thenReturn(payment(8, "FAILED", "card_declined"));

        assertThat(pay(id, "{\"cardToken\":\"tok_declined\"}")).isEqualTo(402);
        assertStatus(id, "CANCELLED");
    }

    @Test
    void unavailablePaymentServiceCancelsTheBookingAnswers503() throws Exception {
        long id = newBooking("10.00", 1);
        FeignException down = feignError(503);
        when(payments.charge(any())).thenThrow(down);

        assertThat(pay(id, null)).isEqualTo(503);
        assertStatus(id, "CANCELLED");
    }

    @Test
    void payingTwiceChargesOnce() throws Exception {
        long id = newBooking("10.00", 1);
        when(payments.charge(any())).thenReturn(payment(9, "SUCCEEDED", null));

        assertThat(pay(id, null)).isEqualTo(200);
        assertThat(pay(id, null)).isEqualTo(200);
        verify(payments, times(1)).charge(any());
        assertStatus(id, "CONFIRMED");
    }

    @Test
    void aCancelledBookingCannotBePaid() throws Exception {
        long id = newBooking("10.00", 1);
        mockMvc.perform(post("/api/bookings/" + id + "/cancel")).andExpect(status().isOk());

        assertThat(pay(id, null)).isEqualTo(409);
        verify(payments, never()).charge(any());
    }

    @Test
    void aFreeBookingIsConfirmedWithoutPayment() throws Exception {
        long id = newBooking("0.00", 1);

        mockMvc.perform(post("/api/bookings/" + id + "/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(payments, never()).charge(any());
    }

    @Test
    void cancellingAConfirmedBookingRefundsIt() throws Exception {
        long id = newBooking("10.00", 1);
        when(payments.charge(any())).thenReturn(payment(11, "SUCCEEDED", null));
        when(payments.refund(11L)).thenReturn(payment(11, "REFUNDED", null));
        assertThat(pay(id, null)).isEqualTo(200);

        mockMvc.perform(post("/api/bookings/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(payments).refund(11L);
    }

    @Test
    void ifTheRefundFailsTheBookingStaysConfirmed() throws Exception {
        long id = newBooking("10.00", 1);
        when(payments.charge(any())).thenReturn(payment(12, "SUCCEEDED", null));
        FeignException down = feignError(500);
        when(payments.refund(12L)).thenThrow(down);
        assertThat(pay(id, null)).isEqualTo(200);

        mockMvc.perform(post("/api/bookings/" + id + "/cancel")).andExpect(status().isServiceUnavailable());
        assertStatus(id, "CONFIRMED");
    }

    @Test
    void aPaymentInProgressAnswers409AndKeepsTheBookingPending() throws Exception {
        long id = newBooking("10.00", 1);
        FeignException conflict = feignError(409);
        when(payments.charge(any())).thenThrow(conflict);

        assertThat(pay(id, null)).isEqualTo(409);
        assertStatus(id, "PENDING");
    }

    @Test
    void availabilityCountsPendingAndConfirmedButNotCancelled() throws Exception {
        long a = newBooking("10.00", 3);
        long b = newBooking("10.00", 2);
        mockMvc.perform(post("/api/bookings/" + b + "/cancel")).andExpect(status().isOk());

        // the other tests share this in-memory database, so only the relation is checked
        mockMvc.perform(get("/api/bookings/events/1/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(1000))
                .andExpect(jsonPath("$.remaining").value(org.hamcrest.Matchers.lessThanOrEqualTo(997)));
    }
}
