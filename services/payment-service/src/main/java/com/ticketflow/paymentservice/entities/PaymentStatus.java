package com.ticketflow.paymentservice.entities;

/**
 * PENDING: the provider is being called. SUCCEEDED / FAILED: its answer. REFUNDED: money given back
 * (only after SUCCEEDED).
 */
public enum PaymentStatus {
    PENDING, SUCCEEDED, FAILED, REFUNDED
}
