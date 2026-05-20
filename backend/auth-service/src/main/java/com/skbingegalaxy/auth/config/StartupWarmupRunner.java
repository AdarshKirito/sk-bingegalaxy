package com.skbingegalaxy.auth.config;

import com.skbingegalaxy.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Pre-warms the most expensive cold-start hot paths so the first real user request
 * isn't paying for JIT compilation + connection-pool initialization + BCrypt
 * one-time class loading.
 *
 * Production parallel: this is the same pattern Netflix/Spotify/Uber use behind
 * the scenes — services run a synthetic warm-up workload at boot so the first
 * customer-facing call sees steady-state latency.
 *
 * What we warm:
 *   1. BCrypt encode + matches (heaviest contributor at strength=12; ~200-400ms cold)
 *   2. JPA / Hibernate first-query path (count() forces session factory + SQL parser)
 *
 * Runs asynchronously after the context is fully ready, so it never blocks
 * readiness probes or extends startup time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupWarmupRunner {

    private static final String WARMUP_PLAINTEXT = "warmup-not-a-real-password";

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    @Async
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        long start = System.currentTimeMillis();
        try {
            // 1) BCrypt hot path — encode then match three times so the JIT optimises it.
            String hash = passwordEncoder.encode(WARMUP_PLAINTEXT);
            for (int i = 0; i < 3; i++) {
                passwordEncoder.matches(WARMUP_PLAINTEXT, hash);
            }

            // 2) JPA cold path — touches Hibernate session factory + JDBC pool.
            try {
                userRepository.count();
            } catch (Exception ignored) {
                // Don't fail warmup if the table isn't reachable yet.
            }

            log.info("Auth warm-up complete in {} ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // Warm-up is best-effort; never crash the service for it.
            log.warn("Auth warm-up failed (non-fatal): {}", e.getMessage());
        }
    }
}
