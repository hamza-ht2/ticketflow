package com.ticketflow.bookingservice.service;

import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.ChargeRequest;
import com.ticketflow.bookingservice.clients.EventView;
import com.ticketflow.bookingservice.clients.PaymentClient;
import com.ticketflow.bookingservice.clients.PaymentView;
import com.ticketflow.bookingservice.dto.AvailabilityResponse;
import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.exceptions.BookingRuleException;
import com.ticketflow.bookingservice.exceptions.PaymentRefusedException;
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
 * The remote calls (event-service, payment-service) happen OUTSIDE the database transaction: a slow service must not
 * keep a database connection busy. The transaction only does the local work.
 */
@Service
public class BookingService {

    private final EventClient events;
    private final PaymentClient payments;
    private final BookingRepo bookings;
    private final SeatInventoryRepo inventory;
    private final SeatInventoryInitializer initializer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingService(EventClient events, PaymentClient payments, BookingRepo bookings, SeatInventoryRepo inventory,
                          SeatInventoryInitializer initializer, TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.payments = payments;
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

    /**
     * Cancels a booking and gives its seats back. A paid booking is refunded FIRST: if the refund
     * cannot be done (payment-service down), the booking stays CONFIRMED and the client retries.
     */
    public Booking cancel(Long id) {
        Booking current = get(id);
        if (current.getStatus() == BookingStatus.CONFIRMED && current.getPaymentId() != null) {
            refund(current.getPaymentId());
        }
        return tx.execute(status -> {
            Booking booking = get(id);
            booking.cancel();
            inventory.release(booking.getEventId(), booking.getQuantity());
            return bookings.save(booking);
        });
    }

    /**
     * Pays a PENDING booking. Idempotent: a CONFIRMED booking is returned as is, and payment-service
     * never charges twice the same booking.
     * <ul>
     *   <li>payment succeeded: CONFIRMED;</li>
     *   <li>payment refused (card declined...): CANCELLED, seats released, HTTP 402;</li>
     *   <li>payment-service unavailable: CANCELLED, seats released, HTTP 503.</li>
     * </ul>
     */
    public Booking pay(Long id, String cardToken) {
        Booking booking = get(id);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return booking;
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingRuleException("A cancelled booking cannot be paid");
        }
        if (booking.getTotalPrice().signum() == 0) {
            return confirm(id, null); // free event: nothing to charge
        }

        PaymentView payment = charge(booking, cardToken);
        if (!"SUCCEEDED".equals(payment.status())) {
            cancelIfPending(id);
            throw new PaymentRefusedException(payment.failureReason() == null ? payment.status() : payment.failureReason());
        }
        Booking confirmed = confirm(id, payment.id());
        if (confirmed == null) {
            // The booking was cancelled while we were paying: give the money back, do not keep it.
            refund(payment.id());
            throw new BookingRuleException("The booking was cancelled during the payment, the payment has been refunded");
        }
        return confirmed;
    }

    /** Calls payment-service (outside any transaction). On failure the seats are released. */
    private PaymentView charge(Booking booking, String cardToken) {
        try {
            return payments.charge(new ChargeRequest(booking.getId(), booking.getTotalPrice(), cardToken));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof FeignException feign && feign.status() == 409) {
                // a payment for this booking is already in progress or has another amount: nothing is lost, the booking stays PENDING
                throw new BookingRuleException("A payment is already in progress for this booking");
            }
            // Timeout, refused connection, 5xx, OPEN circuit: we do not know if the money moved.
            // The booking is cancelled so that the seats are not held forever (see README: reconciliation).
            cancelIfPending(booking.getId());
            throw new UpstreamUnavailableException("payment service", cause);
        }
    }

    private void refund(Long paymentId) {
        try {
            payments.refund(paymentId);
        } catch (RuntimeException e) {
            throw new UpstreamUnavailableException("payment service", unwrap(e));
        }
    }

    /** PENDING -> CONFIRMED in one transaction; null when the booking is not PENDING anymore. */
    private Booking confirm(Long id, Long paymentId) {
        return tx.execute(status -> {
            Booking booking = get(id);
            if (booking.getStatus() != BookingStatus.PENDING) {
                return null;
            }
            booking.confirm(paymentId);
            return bookings.save(booking);
        });
    }

    private void cancelIfPending(Long id) {
        tx.executeWithoutResult(status -> {
            Booking booking = get(id);
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.cancel();
                inventory.release(booking.getEventId(), booking.getQuantity());
                bookings.save(booking);
            }
        });
    }

    /** The circuit breaker wraps the original exception when there is no fallback. */
    private static Throwable unwrap(RuntimeException e) {
        return (e instanceof NoFallbackAvailableException && e.getCause() != null) ? e.getCause() : e;
    }

    /**
     * Seats taken and left for an event. Read from the local counter (no remote call); an event
     * nobody booked yet has no counter, so its capacity comes from event-service.
     */
    public AvailabilityResponse availability(Long eventId) {
        return inventory.findById(eventId)
                .map(i -> AvailabilityResponse.of(eventId, i.getCapacity(), i.getHeld()))
                .orElseGet(() -> AvailabilityResponse.of(eventId, fetchEvent(eventId).capacity(), 0));
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
            Throwable cause = unwrap(e);
            if (cause instanceof FeignException feign && feign.status() == 404) {
                throw new ResourceNotFoundException("Event " + eventId + " not found");
            }
            // Timeout, refused connection, 5xx, or CallNotPermittedException when the circuit is OPEN
            // (the call is then refused at once, event-service is not even contacted)
            throw new UpstreamUnavailableException("event service", cause);
        }
    }
}
