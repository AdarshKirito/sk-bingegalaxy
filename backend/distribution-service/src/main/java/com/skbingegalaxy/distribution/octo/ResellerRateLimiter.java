package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-CONNECTION throttling for the OCTO surface.
 *
 * <p><b>Why per-IP is the wrong axis here, in both directions.</b> The gateway limits
 * 100 requests/minute per IP across all traffic. For a reseller that is simultaneously
 *
 * <ul>
 *   <li><b>too tight</b> — a reseller is a server, not a browser. Its entire integration
 *       (catalogue polls, availability across many dates, bookings) arrives from one or
 *       two addresses and shares that budget with every other caller behind the same
 *       NAT, so a legitimate reseller starves; and</li>
 *   <li><b>too loose</b> — the limit keys on the address, not the key. One reseller
 *       spreading the same key across a handful of hosts multiplies its budget, so the
 *       thing being protected — availability-service, which direct customer bookings
 *       also depend on — has no real ceiling at all. That is risk DIST-R6, and this
 *       became the only defence when the OCTO namespace was made public at the gateway
 *       so reseller keys could reach it.</li>
 * </ul>
 *
 * <p>The connection is the right unit because it is the commercial relationship: one
 * reseller, one venue, one agreed volume.
 *
 * <p><b>In-memory and per-replica, deliberately.</b> The obvious objection is that two
 * replicas allow twice the limit. That is true and acceptable: this is a fairness and
 * blast-radius control, not a billing meter, and the alternative — a Redis round trip on
 * every reseller request — adds a dependency and a latency cost to the hot path in order
 * to make an approximate number slightly less approximate. Redis is also not currently a
 * dependency of this service, and adding one to enforce a soft limit would mean an
 * outage in Redis could stop a channel selling.
 */
@Component
@Slf4j
public class ResellerRateLimiter {

    /**
     * Requests per minute per connection.
     *
     * <p>Generous by browser standards and modest by integration standards: a reseller
     * refreshing a 30-day calendar every few minutes sits far below it, while a runaway
     * polling loop is stopped before it reaches availability-service.
     */
    @Value("${distribution.octo.rate-limit-per-minute:600}")
    private int requestsPerMinute;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Fixed window per connection. Bounded by the number of connections, which is small. */
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        private volatile Instant startedAt = Instant.now();
        private final AtomicInteger count = new AtomicInteger();
    }

    /**
     * @return {@code null} when the request may proceed, or a populated 429 when it may not
     */
    public ResponseEntity<?> check(Connection connection) {
        if (connection == null || requestsPerMinute <= 0) return null;

        Window window = windows.computeIfAbsent(connection.getId(), id -> new Window());
        Instant now = Instant.now();

        synchronized (window) {
            if (Duration.between(window.startedAt, now).compareTo(WINDOW) >= 0) {
                window.startedAt = now;
                window.count.set(0);
            }
            int used = window.count.incrementAndGet();
            if (used > requestsPerMinute) {
                long retryAfter = Math.max(1,
                    WINDOW.getSeconds() - Duration.between(window.startedAt, now).getSeconds());
                // The connection id is safe to log; the key is not, and is not in scope here.
                log.warn("OCTO rate limit hit by connection {} ({} req/min)",
                    connection.getId(), requestsPerMinute);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    // Retry-After is what makes a well-behaved client back off instead of
                    // retrying immediately and deepening the problem it just caused.
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("error", "TOO_MANY_REQUESTS",
                                 "errorMessage",
                                 "Rate limit exceeded. Retry in " + retryAfter + "s."));
            }
        }
        return null;
    }

    /** Test seam: forget every window. */
    void reset() {
        windows.clear();
    }
}
