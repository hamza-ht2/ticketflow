package com.ticketflow.bookingservice.dto;

import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record BookingResponse(
        Long id,
        Long eventId,
        String eventTitle,
        Long customerId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        BookingStatus status,
        Long paymentId,
        Instant createdAt) {

    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getEventId(), b.getEventTitle(), b.getCustomerId(),
                b.getQuantity(), b.getUnitPrice(), b.getTotalPrice(), b.getStatus(), b.getPaymentId(), b.getCreatedAt());
    }
}
