package com.ticketflow.bookingservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Client of payment-service. "payment-service" is the Eureka name (load-balanced); the optional url
 * overrides it and bypasses Eureka. Its own circuit breaker is configured in config/ResilienceConfig.
 */
@FeignClient(name = "payment-service", url = "${ticketflow.clients.payment-service.url:}")
public interface PaymentClient {

    /** Idempotent on the booking id: calling it twice for the same booking never charges twice. */
    @PostMapping("/api/payments")
    PaymentView charge(@RequestBody ChargeRequest request);

    /** Idempotent: refunding an already refunded payment answers 200 with the same payment. */
    @PostMapping("/api/payments/{id}/refund")
    PaymentView refund(@PathVariable("id") Long id);
}
