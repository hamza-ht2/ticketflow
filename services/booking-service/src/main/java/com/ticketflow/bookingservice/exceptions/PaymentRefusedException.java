package com.ticketflow.bookingservice.exceptions;

/** The payment provider refused the payment (declined card, insufficient funds...): HTTP 402. */
public class PaymentRefusedException extends RuntimeException {
    public PaymentRefusedException(String reason) {
        super("Payment refused: " + reason + ". The booking has been cancelled and its seats released");
    }
}
