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
 * Circuit breaker of the event-service client. The Feign client is protected as soon as
 * {@code spring.cloud.openfeign.circuitbreaker.enabled=true}; this class only says HOW.
 * <p>
 * Booking-service has a single remote dependency, so one default configuration is enough. With
 * several dependencies, each one would get its own circuit breaker and its own thresholds.
 */
@Configuration
@EnableConfigurationProperties(EventServiceResilienceProperties.class)
public class ResilienceConfig {

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> eventServiceCircuitBreaker(EventServiceResilienceProperties p) {
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumNumberOfCalls())
                .failureRateThreshold(p.failureRateThreshold())
                .waitDurationInOpenState(p.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpenState())
                // 404 and 400 are valid answers of a healthy service (unknown event, bad request):
                // they must never count as failures nor open the circuit.
                .ignoreExceptions(FeignException.NotFound.class, FeignException.BadRequest.class)
                .build();
        // The default time limit of Spring Cloud Circuit Breaker is 1 s, shorter than the Feign read
        // timeout: it would cut healthy calls. Keep it above the read timeout, so Feign fails first.
        TimeLimiterConfig limiter = TimeLimiterConfig.custom()
                .timeoutDuration(p.timeLimit())
                .build();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(breaker)
                .timeLimiterConfig(limiter)
                .build());
    }
}
