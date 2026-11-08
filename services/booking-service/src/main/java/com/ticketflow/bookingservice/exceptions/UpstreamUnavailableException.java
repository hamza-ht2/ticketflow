package com.ticketflow.bookingservice.exceptions;

/** Another service did not answer in time or failed: HTTP 503. The circuit breaker (config/ResilienceConfig) makes it fail fast. */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String service, Throwable cause) {
        super("The " + service + " is temporarily unavailable, please retry", cause);
    }
}
