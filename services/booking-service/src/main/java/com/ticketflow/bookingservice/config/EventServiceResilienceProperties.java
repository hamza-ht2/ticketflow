package com.ticketflow.bookingservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning of the circuit breaker that protects the calls to event-service
 * (prefix {@code ticketflow.resilience.event-service}). Every value has a default, and can be
 * changed without touching the code, for example from the Config Server.
 *
 * @param slidingWindowSize              number of recent calls used to compute the failure rate
 * @param minimumNumberOfCalls           calls needed before the rate is evaluated (avoids opening on 1 error)
 * @param failureRateThreshold           percentage of failed calls that opens the circuit
 * @param waitInOpenState                how long the circuit stays OPEN before testing the service again
 * @param permittedCallsInHalfOpenState  test calls allowed while HALF_OPEN
 * @param timeLimit                      longest duration of one call, kept above the Feign read timeout
 */
@ConfigurationProperties(prefix = "ticketflow.resilience.event-service")
public record EventServiceResilienceProperties(
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration waitInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState,
        @DefaultValue("4s") Duration timeLimit) {
}
