package com.ticketflow.paymentservice.entities;

import com.ticketflow.paymentservice.exceptions.PaymentRuleException;
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

/**
 * One payment per booking: the unique column {@code bookingId} is what prevents a double
 * charge when the same request arrives twice, even at the same time.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The booking this payment is for. Not checked against booking-service: it is the caller. */
    @Column(nullable = false, unique = true)
    private Long bookingId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** The provider's own identifier for the charge (needed to refund it). */
    @Column(length = 60)
    private String providerReference;

    @Column(length = 100)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() { // required by JPA
    }

    public Payment(Long bookingId, BigDecimal amount, Instant now) {
        this.bookingId = bookingId;
        this.amount = amount;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** FAILED -> PENDING: the customer tries again (another card, for instance). */
    public void retry(BigDecimal newAmount, Instant now) {
        if (status != PaymentStatus.FAILED) {
            throw new PaymentRuleException("Only a failed payment can be retried");
        }
        this.amount = newAmount;
        this.status = PaymentStatus.PENDING;
        this.failureReason = null;
        this.updatedAt = now;
    }

    public void succeed(String reference, Instant now) {
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = reference;
        this.failureReason = null;
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    /** SUCCEEDED -> REFUNDED. */
    public void refund(Instant now) {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new PaymentRuleException("Only a succeeded payment can be refunded");
        }
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
