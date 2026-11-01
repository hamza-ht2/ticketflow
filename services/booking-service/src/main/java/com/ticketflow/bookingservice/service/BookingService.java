package com.ticketflow.bookingservice.service;

import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventView;
import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.exceptions.BookingRuleException;
import com.ticketflow.bookingservice.exceptions.ResourceNotFoundException;
import com.ticketflow.bookingservice.exceptions.UpstreamUnavailableException;
import com.ticketflow.bookingservice.repos.BookingRepo;
import com.ticketflow.bookingservice.repos.SeatInventoryRepo;
import feign.FeignException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The remote call (event-service) happens BEFORE the database transaction: a slow service must not
 * keep a database connection busy. The transaction only does the local work.
 */
@Service
public class BookingService {

    private final EventClient events;
    private final BookingRepo bookings;
    private final SeatInventoryRepo inventory;
    private final SeatInventoryInitializer initializer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingService(EventClient events, BookingRepo bookings, SeatInventoryRepo inventory,
                          SeatInventoryInitializer initializer, TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.bookings = bookings;
        this.inventory = inventory;
        this.initializer = initializer;
        this.tx = tx;
        this.clock = clock;
    }

    /** Books {@code quantity} seats of an event for a customer. */
    public Booking book(Long eventId, Long customerId, int quantity) {
        EventView event = fetchEvent(eventId);
        if (!"PUBLISHED".equals(event.status())) {
            throw new BookingRuleException("The event is not open for booking");
        }
        if (!event.startsAt().isAfter(Instant.now(clock))) {
            throw new BookingRuleException("The event has already started");
        }

        try {
            initializer.ensureExists(eventId, event.capacity());
        } catch (DataAccessException concurrentCreation) {
            // another request created the counter first: that is fine, it exists now
        }
        return tx.execute(status -> {
            if (inventory.reserve(eventId, quantity, event.capacity()) == 0) {
                throw new BookingRuleException("Not enough seats left for this event");
            }
            return bookings.save(new Booking(eventId, event.title(), customerId,
                    quantity, event.price(), Instant.now(clock)));
        });
    }

    /** Cancels a booking and gives its seats back. */
    public Booking cancel(Long id) {
        return tx.execute(status -> {
            Booking booking = get(id);
            booking.cancel();
            inventory.release(booking.getEventId(), booking.getQuantity());
            return bookings.save(booking);
        });
    }

    public Booking get(Long id) {
        return bookings.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + id + " not found"));
    }

    /** All bookings, or only those of one customer. Newest first. */
    public List<Booking> list(Long customerId) {
        return customerId == null
                ? bookings.findAllByOrderByCreatedAtDescIdDesc()
                : bookings.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId);
    }

    /**
     * Reads the event. Business answers keep their meaning (unknown event = 404); everything else
     * (timeout, connection refused, 5xx, open circuit) means "event-service is not usable now" = 503.
     */
    private EventView fetchEvent(Long eventId) {
        try {
            return events.get(eventId);
        } catch (RuntimeException e) {
            // The circuit breaker wraps the original exception when there is no fallback
            Throwable cause = (e instanceof NoFallbackAvailableException && e.getCause() != null)
                    ? e.getCause() : e;
            if (cause instanceof FeignException feign && feign.status() == 404) {
                throw new ResourceNotFoundException("Event " + eventId + " not found");
            }
            // Timeout, refused connection, 5xx, or CallNotPermittedException when the circuit is OPEN
            // (the call is then refused at once, event-service is not even contacted)
            throw new UpstreamUnavailableException("event service", cause);
        }
    }
}
