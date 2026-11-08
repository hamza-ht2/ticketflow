package com.ticketflow.paymentservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What the caller (booking-service) sends. {@code cardToken} stands for a card: the simulated provider
 * decides the outcome from it (see SimulatedPaymentProvider). It is optional and defaults to a success.
 */
public record PaymentRequest(
        @NotNull @Positive Long bookingId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Size(max = 50) String cardToken) {
}
