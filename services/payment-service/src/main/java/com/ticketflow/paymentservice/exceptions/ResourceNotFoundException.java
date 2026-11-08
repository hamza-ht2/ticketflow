package com.ticketflow.paymentservice.exceptions;

/** A payment does not exist: HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
