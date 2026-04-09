package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void firstRequest_isAllowed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void underLimit_allAllowed() {
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .remoteAddress(new InetSocketAddress("10.0.0.2", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        verify(chain, times(100)).filter(any());
    }

    @Test
    void overLimit_returns429() {
        // Exhaust all 100 tokens
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .remoteAddress(new InetSocketAddress("10.0.0.99", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        // 101st request should be rate-limited
        MockServerWebExchange exchange101 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .remoteAddress(new InetSocketAddress("10.0.0.99", 12345))
                        .build());
        filter.filter(exchange101, chain).block();

        assertThat(exchange101.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange101.getResponse().getHeaders().getFirst("X-RateLimit-Retry-After")).isEqualTo("60");
    }

    @Test
    void differentIps_haveSeparateBuckets() {
        // Exhaust tokens for IP-A
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .remoteAddress(new InetSocketAddress("10.0.0.200", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        // IP-B should still be allowed
        MockServerWebExchange exchangeB = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .remoteAddress(new InetSocketAddress("10.0.0.201", 12345))
                        .build());
        filter.filter(exchangeB, chain).block();

        assertThat(exchangeB.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void authPath_hasTighterLimit() {
        // Auth paths should have a limit of 10 per minute
        for (int i = 0; i < 10; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/auth/login")
                            .remoteAddress(new InetSocketAddress("10.0.0.50", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        // 11th auth request should be rate-limited
        MockServerWebExchange exchange11 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("10.0.0.50", 12345))
                        .build());
        filter.filter(exchange11, chain).block();

        assertThat(exchange11.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void xForwardedFor_lastEntryUsedWhenPeerIsPrivate() {
        // When remote peer is a private IP (proxy), the LAST X-Forwarded-For entry is trusted
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Forwarded-For", "198.51.100.10, 10.0.0.10")
                            .remoteAddress(new InetSocketAddress("10.10.10.10", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        // Same last entry (10.0.0.10) should be rate-limited, even with different first entry
        MockServerWebExchange sameLastEntry = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Forwarded-For", "198.51.100.99, 10.0.0.10")
                        .remoteAddress(new InetSocketAddress("10.10.10.10", 12345))
                        .build());

        filter.filter(sameLastEntry, chain).block();

        assertThat(sameLastEntry.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void publicPeer_ignoresProxyHeaders() {
        // When remote peer is a PUBLIC IP, proxy headers are ignored — rate by connection IP
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Forwarded-For", "203.0.113.10")
                            .remoteAddress(new InetSocketAddress("198.51.100.1", 12345))
                            .build());
            filter.filter(exchange, chain).block();
        }

        // Even with a different X-Forwarded-For, should still be blocked (same connection IP)
        MockServerWebExchange spoofedIp = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Forwarded-For", "203.0.113.99")
                        .remoteAddress(new InetSocketAddress("198.51.100.1", 12345))
                        .build());

        filter.filter(spoofedIp, chain).block();

        assertThat(spoofedIp.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void order_isNegativeTwo() {
        assertThat(filter.getOrder()).isEqualTo(-2);
    }
}
