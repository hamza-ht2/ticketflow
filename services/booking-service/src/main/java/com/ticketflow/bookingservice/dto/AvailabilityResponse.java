package com.ticketflow.bookingservice.dto;

/** Seats of an event: {@code remaining = capacity - held}. Held = seats of PENDING and CONFIRMED bookings. */
public record AvailabilityResponse(Long eventId, int capacity, int held, int remaining) {

    public static AvailabilityResponse of(Long eventId, int capacity, int held) {
        return new AvailabilityResponse(eventId, capacity, held, capacity - held);
    }
}
