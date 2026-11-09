package com.ticketflow.bookingservice.entities;

import com.ticketflow.bookingservice.exceptions.BookingRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    /** Copied when booking: the title (and the price) shown later must not change if the event does. */
    @Column(nullable = false, length = 150)
    private String eventTitle;

    /** Not checked against customer-service yet: the identity comes from the token in a later step. */
    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    /** Id of the payment in payment-service (no foreign key: another database). Null until paid. */
    private Long paymentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Booking() { // required by JPA
    }

    public Booking(Long eventId, String eventTitle, Long customerId, int quantity,
                   BigDecimal unitPrice, Instant createdAt) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        this.eventId = eventId;
        this.eventTitle = eventTitle;
        this.customerId = customerId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        this.createdAt = createdAt;
    }

    /** PENDING -> CONFIRMED, once the payment succeeded. A free booking has no payment (paymentId null). */
    public void confirm(Long paymentId) {
        if (status != BookingStatus.PENDING) {
            throw new BookingRuleException("Only a pending booking can be confirmed");
        }
        status = BookingStatus.CONFIRMED;
        this.paymentId = paymentId;
    }

    /** PENDING or CONFIRMED -> CANCELLED. */
    public void cancel() {
        if (status == BookingStatus.CANCELLED) {
            throw new BookingRuleException("The booking is already cancelled");
        }
        status = BookingStatus.CANCELLED;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getEventTitle() { return eventTitle; }
    public Long getCustomerId() { return customerId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public BookingStatus getStatus() { return status; }
    public Long getPaymentId() { return paymentId; }
    public Instant getCreatedAt() { return createdAt; }
}
