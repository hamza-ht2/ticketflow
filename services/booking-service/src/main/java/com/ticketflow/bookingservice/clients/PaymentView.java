package com.ticketflow.bookingservice.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** What booking-service needs to know about a payment. status: PENDING, SUCCEEDED, FAILED, REFUNDED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentView(Long id, Long bookingId, BigDecimal amount, String status, String failureReason) {
}
