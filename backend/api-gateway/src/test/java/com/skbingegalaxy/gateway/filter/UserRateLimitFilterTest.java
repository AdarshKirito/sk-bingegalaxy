package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRateLimitFilterTest {

    private UserRateLimitFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new UserRateLimitFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange authed(HttpMethod method, String path, String userId) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.method(method, path)
                .header("X-User-Id", userId)
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .build());
    }

    private MockServerWebExchange anonymous(HttpMethod method, String path, String ip) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.method(method, path)
                .remoteAddress(new InetSocketAddress(ip, 12345))
                .build());
    }

    @Test
    void nonMatchingPath_alwaysPassesThrough() {
        // /api/test is not in the rule set — filter should be a no-op.
        for (int i = 0; i < 50; i++) {
            filter.filter(authed(HttpMethod.GET, "/api/test", "42"), chain).block();
        }
        verify(chain, times(50)).filter(any());
    }

    @Test
    void bookingCreate_underLimit_allowed() {
        for (int i = 0; i < 10; i++) {
            filter.filter(authed(HttpMethod.POST, "/api/v1/bookings", "7"), chain).block();
        }
        verify(chain, times(10)).filter(any());
    }

    @Test
    void bookingCreate_overLimit_returns429() {
        // Exhaust the 10-per-minute budget for this user.
        for (int i = 0; i < 10; i++) {
            filter.filter(authed(HttpMethod.POST, "/api/v1/bookings", "99"), chain).block();
        }
        MockServerWebExchange rejected = authed(HttpMethod.POST, "/api/v1/bookings", "99");
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        // Chain was called exactly 10 times for user 99 — the 11th was rejected.
        verify(chain, times(10)).filter(any());
    }

    @Test
    void differentUsers_haveIndependentBuckets() {
        for (int i = 0; i < 10; i++) {
            filter.filter(authed(HttpMethod.POST, "/api/v1/bookings", "user-a"), chain).block();
        }
        // user-a exhausted. user-b must still be allowed.
        MockServerWebExchange userBRequest = authed(HttpMethod.POST, "/api/v1/bookings", "user-b");
        filter.filter(userBRequest, chain).block();

        assertThat(userBRequest.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, times(11)).filter(any());
    }

    @Test
    void forgotPassword_anonymous_keyedByIp() {
        // forgot-password is 5 per 15 min. No X-User-Id; should key on IP.
        String ip = "198.51.100.5";
        for (int i = 0; i < 5; i++) {
            filter.filter(anonymous(HttpMethod.POST, "/api/v1/auth/forgot-password", ip), chain).block();
        }
        MockServerWebExchange rejected = anonymous(HttpMethod.POST, "/api/v1/auth/forgot-password", ip);
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("900"); // 15 min
    }

    @Test
    void paymentInitiate_separateBucketFromBooking() {
        // Exhaust booking bucket for user-c (10 req).
        for (int i = 0; i < 10; i++) {
            filter.filter(authed(HttpMethod.POST, "/api/v1/bookings", "user-c"), chain).block();
        }
        // Payment bucket is independent — same user should still be allowed.
        MockServerWebExchange paymentRequest = authed(HttpMethod.POST, "/api/v1/payments/initiate", "user-c");
        filter.filter(paymentRequest, chain).block();

        assertThat(paymentRequest.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void wrongMethodOnMatchedPath_passesThrough() {
        // Rule only fires for POST /api/v1/bookings. A GET should bypass the filter entirely.
        for (int i = 0; i < 50; i++) {
            filter.filter(authed(HttpMethod.GET, "/api/v1/bookings", "42"), chain).block();
        }
        verify(chain, times(50)).filter(any());
    }

    @Test
    void order_isZero() {
        // Must run AFTER JwtAuthenticationFilter (order -1) so X-User-Id is populated.
        assertThat(filter.getOrder()).isEqualTo(0);
    }
}
