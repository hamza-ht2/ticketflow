package com.ticketflow.bookingservice.clients;

import java.math.BigDecimal;

/** Body of POST /api/payments (payment-service). */
public record ChargeRequest(Long bookingId, BigDecimal amount, String cardToken) {
}
