package com.ticketflow.paymentservice.controllers;

import com.ticketflow.paymentservice.dto.PaymentRequest;
import com.ticketflow.paymentservice.dto.PaymentResponse;
import com.ticketflow.paymentservice.service.PaymentService;
import com.ticketflow.paymentservice.service.PaymentService.Outcome;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 201 + Location when this request created the payment; 200 when the booking already had one (the same
     * request sent again, or a retry after a failure). A refused card is a normal result: the status in the
     * body is FAILED, not an HTTP error.
     */
    @PostMapping
    ResponseEntity<PaymentResponse> charge(@Valid @RequestBody PaymentRequest request) {
        Outcome outcome = service.charge(request.bookingId(), request.amount(), request.cardToken());
        PaymentResponse body = PaymentResponse.from(outcome.payment());
        if (!outcome.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    /** All payments, newest first. Optional {@code ?bookingId=} filter. */
    @GetMapping
    List<PaymentResponse> list(@RequestParam(required = false) Long bookingId) {
        return service.list(bookingId).stream().map(PaymentResponse::from).toList();
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable Long id) {
        return PaymentResponse.from(service.get(id));
    }

    @PostMapping("/{id}/refund")
    PaymentResponse refund(@PathVariable Long id) {
        return PaymentResponse.from(service.refund(id));
    }
}
