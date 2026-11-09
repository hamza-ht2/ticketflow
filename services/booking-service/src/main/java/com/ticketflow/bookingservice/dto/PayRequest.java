package com.ticketflow.bookingservice.dto;

import jakarta.validation.constraints.Size;

/** Optional body of POST /api/bookings/{id}/pay. The card token comes from the payment form, never a card number. */
public record PayRequest(@Size(max = 50) String cardToken) {
}
