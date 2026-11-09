package com.ticketflow.paymentservice.exceptions;

/** The payment provider cannot be reached: HTTP 503. The payment is left FAILED so it can be retried. */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message) {
        super(message);
    }
}
