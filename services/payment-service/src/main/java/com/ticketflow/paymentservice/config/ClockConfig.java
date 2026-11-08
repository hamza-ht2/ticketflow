package com.ticketflow.paymentservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** One clock for the whole service: tests can replace it to control "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
