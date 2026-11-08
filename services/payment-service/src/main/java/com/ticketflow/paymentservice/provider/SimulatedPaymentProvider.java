package com.ticketflow.paymentservice.provider;

import com.ticketflow.paymentservice.exceptions.ProviderUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A fake provider, deterministic on purpose: the outcome depends only on the card token, so a test (or a
 * demo) can produce each case on demand.
 * <pre>
 *   (none) or tok_success     charge succeeds
 *   tok_declined              refused: card_declined
 *   tok_insufficient_funds    refused: insufficient_funds
 *   tok_slow                  succeeds, but only after a delay (to see timeouts and circuit breakers)
 *   tok_error                 the provider is unreachable (exception)
 *   anything else             refused: unknown_card
 * </pre>
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private final Duration slowDelay;

    public SimulatedPaymentProvider(@Value("${ticketflow.payment.simulated.slow-delay:5s}") Duration slowDelay) {
        this.slowDelay = slowDelay;
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String cardToken) {
        String token = (cardToken == null || cardToken.isBlank()) ? "tok_success" : cardToken;
        return switch (token) {
            case "tok_success" -> ChargeResult.success(newReference());
            case "tok_declined" -> ChargeResult.refused("card_declined");
            case "tok_insufficient_funds" -> ChargeResult.refused("insufficient_funds");
            case "tok_slow" -> {
                pause();
                yield ChargeResult.success(newReference());
            }
            case "tok_error" -> throw new ProviderUnavailableException("The payment provider is unreachable");
            default -> ChargeResult.refused("unknown_card");
        };
    }

    @Override
    public void refund(String providerReference) {
        // Nothing to call: the simulation always accepts a refund.
    }

    private static String newReference() {
        return "sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void pause() {
        try {
            Thread.sleep(slowDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
