package com.ticketflow.bookingservice.entities;

/**
 * PENDING: seats are held, waiting for the payment (POST /api/bookings/{id}/pay).
 * CONFIRMED: paid (or free), seats kept. CANCELLED: seats released (cancelled, payment refused or
 * payment-service unavailable).
 */
public enum BookingStatus {
    PENDING, CONFIRMED, CANCELLED;

    /** Does a booking in this status hold seats? */
    public boolean holdsSeats() {
        return this != CANCELLED;
    }
}
