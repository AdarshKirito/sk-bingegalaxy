package com.skbingegalaxy.availability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * System clock as a Spring bean so time-dependent logic (venue-local "now"
 * comparisons in {@link com.skbingegalaxy.availability.service.AvailabilityService})
 * can be exercised deterministically in tests via a fixed {@link Clock}, instead
 * of calling {@code LocalDateTime.now()} directly.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
