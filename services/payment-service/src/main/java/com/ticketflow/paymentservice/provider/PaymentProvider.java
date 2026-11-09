package com.ticketflow.paymentservice.provider;

import com.ticketflow.paymentservice.exceptions.ProviderUnavailableException;
import java.math.BigDecimal;

/**
 * The only thing the business code knows about a payment provider. Today there is one implementation, a
 * simulation; a real provider (PayPal sandbox, Stripe...) would be another implementation of this interface,
 * with no change in PaymentService.
 */
public interface PaymentProvider {

    /** Charges {@code amount}. A refusal is a normal result; an unreachable provider is an exception. */
    ChargeResult charge(BigDecimal amount, String cardToken) throws ProviderUnavailableException;

    /** Gives the money of a previous charge back. */
    void refund(String providerReference) throws ProviderUnavailableException;

    /** {@code reference} is set on success, {@code failureReason} on refusal. */
    record ChargeResult(boolean succeeded, String reference, String failureReason) {

        public static ChargeResult success(String reference) {
            return new ChargeResult(true, reference, null);
        }

        public static ChargeResult refused(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }
}
