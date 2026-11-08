package com.ticketflow.paymentservice.service;

import com.ticketflow.paymentservice.entities.Payment;
import com.ticketflow.paymentservice.entities.PaymentStatus;
import com.ticketflow.paymentservice.exceptions.PaymentRuleException;
import com.ticketflow.paymentservice.exceptions.ProviderUnavailableException;
import com.ticketflow.paymentservice.exceptions.ResourceNotFoundException;
import com.ticketflow.paymentservice.provider.PaymentProvider;
import com.ticketflow.paymentservice.provider.PaymentProvider.ChargeResult;
import com.ticketflow.paymentservice.repos.PaymentRepo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Three short steps, and the provider call is NEVER inside a database transaction:
 * <ol>
 *   <li>CLAIM the booking: insert a PENDING payment. The unique constraint on bookingId makes sure only one
 *       request wins, even if the same request arrives twice at the same time.</li>
 *   <li>CALL the provider (slow, may fail).</li>
 *   <li>RECORD the answer: SUCCEEDED or FAILED.</li>
 * </ol>
 * Charging the same booking again is therefore safe (idempotent): the existing payment is returned, no second
 * charge is made.
 */
@Service
public class PaymentService {

    /** {@code created}: a new payment row. {@code attempt}: the provider must be called. */
    private record Claim(Payment payment, boolean created, boolean attempt) {
    }

    /** What the caller gets back: the payment, and whether it was created by this very request. */
    public record Outcome(Payment payment, boolean created) {
    }

    private final PaymentRepo payments;
    private final PaymentProvider provider;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepo payments, PaymentProvider provider, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.provider = provider;
        this.tx = tx;
        this.clock = clock;
    }

    public Outcome charge(Long bookingId, BigDecimal amount, String cardToken) {
        Claim claim = claim(bookingId, amount);
        if (!claim.attempt()) {
            return new Outcome(claim.payment(), false); // replay: nothing is charged again
        }
        Long id = claim.payment().getId();
        ChargeResult result = callProvider(id, amount, cardToken);
        Payment done = update(id, p -> {
            if (result.succeeded()) {
                p.succeed(result.reference(), now());
            } else {
                p.fail(result.failureReason(), now());
            }
        });
        return new Outcome(done, claim.created());
    }

    /** Idempotent: refunding a refunded payment returns it unchanged. */
    public Payment refund(Long id) {
        Payment payment = get(id);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new PaymentRuleException("Only a succeeded payment can be refunded");
        }
        provider.refund(payment.getProviderReference()); // outside the transaction
        return update(id, p -> p.refund(now()));
    }

    public Payment get(Long id) {
        return payments.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + id + " not found"));
    }

    public List<Payment> list(Long bookingId) {
        if (bookingId == null) {
            return payments.findAllByOrderByCreatedAtDescIdDesc();
        }
        return payments.findByBookingId(bookingId).map(List::of).orElse(List.of());
    }

    // ---- steps ----

    private Claim claim(Long bookingId, BigDecimal amount) {
        try {
            return tx.execute(status -> {
                Payment existing = payments.findByBookingId(bookingId).orElse(null);
                if (existing == null) {
                    return new Claim(payments.saveAndFlush(new Payment(bookingId, amount, now())), true, true);
                }
                switch (existing.getStatus()) {
                    case FAILED -> { // the customer tries again: same payment row, new attempt
                        existing.retry(amount, now());
                        return new Claim(payments.save(existing), false, true);
                    }
                    case PENDING -> throw new PaymentRuleException(
                            "A payment for this booking is already in progress");
                    default -> { // SUCCEEDED or REFUNDED: same request again, or a conflicting one
                        if (existing.getAmount().compareTo(amount) != 0) {
                            throw new PaymentRuleException(
                                    "A payment already exists for this booking with another amount");
                        }
                        return new Claim(existing, false, false);
                    }
                }
            });
        } catch (DataIntegrityViolationException lostTheRace) {
            // another request inserted the payment of this booking between our read and our insert
            throw new PaymentRuleException("A payment for this booking is already in progress");
        }
    }

    private ChargeResult callProvider(Long id, BigDecimal amount, String cardToken) {
        try {
            return provider.charge(amount, cardToken);
        } catch (ProviderUnavailableException e) {
            update(id, p -> p.fail("provider_unavailable", now())); // so that the customer can retry
            throw e;
        }
    }

    private Payment update(Long id, Consumer<Payment> change) {
        return tx.execute(status -> {
            Payment p = payments.findById(id).orElseThrow();
            change.accept(p);
            return payments.save(p);
        });
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
