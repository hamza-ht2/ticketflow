package com.ticketflow.bookingservice.config;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breakers of the two remote dependencies. They are protected as soon as
 * {@code spring.cloud.openfeign.circuitbreaker.enabled=true}; this class only says HOW, per client:
 * event-service (reads) and payment-service (money), each with its own thresholds.
 * <p>
 * The circuit breaker id of a Feign call is its method key ("PaymentClient#charge(ChargeRequest)"),
 * so the client is recognised by the interface name.
 */
@Configuration
@EnableConfigurationProperties({EventServiceResilienceProperties.class, PaymentServiceResilienceProperties.class})
public class ResilienceConfig {

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> circuitBreakers(EventServiceResilienceProperties event,
                                                                  PaymentServiceResilienceProperties payment) {
        // 404 and 400 are valid answers of a healthy service (unknown event, bad request): they must
        // never count as failures nor open the circuit.
        CircuitBreakerConfig eventBreaker = CircuitBreakerConfig.custom()
                .slidingWindowSize(event.slidingWindowSize())
                .minimumNumberOfCalls(event.minimumNumberOfCalls())
                .failureRateThreshold(event.failureRateThreshold())
                .waitDurationInOpenState(event.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(event.permittedCallsInHalfOpenState())
                .ignoreExceptions(FeignException.NotFound.class, FeignException.BadRequest.class)
                .enableAutomaticTransitionFromOpenToHalfOpen()
                .build();
        // For payment-service: 400 (invalid request) and 409 (payment already in progress) are
        // business answers too. A refused card is a 2xx answer (status FAILED), not an error at all.
        CircuitBreakerConfig paymentBreaker = CircuitBreakerConfig.custom()
                .slidingWindowSize(payment.slidingWindowSize())
                .minimumNumberOfCalls(payment.minimumNumberOfCalls())
                .failureRateThreshold(payment.failureRateThreshold())
                .waitDurationInOpenState(payment.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(payment.permittedCallsInHalfOpenState())
                .ignoreExceptions(FeignException.NotFound.class, FeignException.BadRequest.class,
                        FeignException.Conflict.class)
                .enableAutomaticTransitionFromOpenToHalfOpen()
                .build();
        // The default time limit of Spring Cloud Circuit Breaker is 1 s, shorter than the Feign read
        // timeout: it would cut healthy calls. Keep it above the read timeout, so Feign fails first.
        TimeLimiterConfig eventLimiter = TimeLimiterConfig.custom().timeoutDuration(event.timeLimit()).build();
        TimeLimiterConfig paymentLimiter = TimeLimiterConfig.custom().timeoutDuration(payment.timeLimit()).build();

        return factory -> factory.configureDefault(id -> id.startsWith("PaymentClient")
                ? new Resilience4JConfigBuilder(id)
                        .circuitBreakerConfig(paymentBreaker).timeLimiterConfig(paymentLimiter).build()
                : new Resilience4JConfigBuilder(id)
                        .circuitBreakerConfig(eventBreaker).timeLimiterConfig(eventLimiter).build());
    }
}
