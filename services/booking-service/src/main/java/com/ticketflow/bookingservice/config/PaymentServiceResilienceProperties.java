package com.ticketflow.bookingservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning of the circuit breaker that protects the calls to payment-service
 * (prefix {@code ticketflow.resilience.payment-service}). Same meaning as for event-service.
 */
@ConfigurationProperties(prefix = "ticketflow.resilience.payment-service")
public record PaymentServiceResilienceProperties(
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration waitInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState,
        @DefaultValue("4s") Duration timeLimit) {
}
