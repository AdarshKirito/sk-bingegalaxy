package com.skbingegalaxy.distribution.octo;

import com.skbingegalaxy.distribution.entity.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-connection throttling for the OCTO surface.
 *
 * <p>This became the only real ceiling on reseller traffic once the OCTO namespace was
 * made public at the gateway so reseller keys could reach it. The gateway's 100/min/IP is
 * both too tight for a server-to-server integration and too loose against a key spread
 * across several hosts — and what it protects, availability-service, is what direct
 * customer bookings also depend on.
 */
@DisplayName("OCTO per-reseller rate limit")
class ResellerRateLimiterTest {

    private ResellerRateLimiter limiter;

    private static Connection connection(long id) {
        return Connection.builder().id(id).bingeId(1L).providerCode("SIMULATOR").build();
    }

    @BeforeEach
    void setUp() {
        limiter = new ResellerRateLimiter();
        ReflectionTestUtils.setField(limiter, "requestsPerMinute", 3);
    }

    @Test
    @DisplayName("allows traffic up to the limit, then refuses with 429")
    void refusesBeyondTheLimit() {
        Connection c = connection(5L);

        assertThat(limiter.check(c)).isNull();
        assertThat(limiter.check(c)).isNull();
        assertThat(limiter.check(c)).isNull();

        ResponseEntity<?> refused = limiter.check(c);
        assertThat(refused).isNotNull();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("says when to come back, so a client backs off instead of hammering")
    void sendsRetryAfter() {
        Connection c = connection(5L);
        for (int i = 0; i < 3; i++) limiter.check(c);

        ResponseEntity<?> refused = limiter.check(c);

        // Without Retry-After a well-behaved client retries immediately and deepens the
        // problem it just caused.
        assertThat(refused.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("one reseller's flood does not spend another's budget")
    void budgetsAreIndependent() {
        Connection noisy = connection(5L);
        Connection quiet = connection(6L);

        for (int i = 0; i < 10; i++) limiter.check(noisy);

        // The whole reason the connection is the unit: it is the commercial
        // relationship, so one venue's channel cannot be starved by another's.
        assertThat(limiter.check(quiet)).isNull();
    }

    @Test
    @DisplayName("a limit of zero disables throttling rather than blocking everything")
    void zeroDisables() {
        ReflectionTestUtils.setField(limiter, "requestsPerMinute", 0);

        // Misreading "unset" as "allow nothing" would take every channel offline the
        // first time someone cleared the property.
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.check(connection(5L))).isNull();
        }
    }

    @Test
    @DisplayName("a null connection is not throttled into a NullPointerException")
    void nullConnectionIsSafe() {
        assertThat(limiter.check(null)).isNull();
    }
}
