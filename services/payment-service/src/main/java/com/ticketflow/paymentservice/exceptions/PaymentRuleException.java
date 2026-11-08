package com.ticketflow.paymentservice.exceptions;

/** A payment rule is violated (wrong state, other amount, already in progress): HTTP 409. */
public class PaymentRuleException extends RuntimeException {
    public PaymentRuleException(String message) {
        super(message);
    }
}
