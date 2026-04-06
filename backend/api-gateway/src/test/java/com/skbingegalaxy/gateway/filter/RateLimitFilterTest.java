package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

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
                MockServerHttpRequest.get("/api/test").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void underLimit_allAllowed() {
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Forwarded-For", "10.0.0.1")
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
                            .header("X-Forwarded-For", "10.0.0.99")
                            .build());
            filter.filter(exchange, chain).block();
        }

        // 101st request should be rate-limited
        MockServerWebExchange exchange101 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Forwarded-For", "10.0.0.99")
                        .build());
        filter.filter(exchange101, chain).block();

        assertThat(exchange101.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange101.getResponse().getHeaders().getFirst("X-RateLimit-Retry-After")).isEqualTo("1");
    }

    @Test
    void differentIps_haveSeparateBuckets() {
        // Exhaust tokens for IP-A
        for (int i = 0; i < 100; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header("X-Forwarded-For", "10.0.0.200")
                            .build());
            filter.filter(exchange, chain).block();
        }

        // IP-B should still be allowed
        MockServerWebExchange exchangeB = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Forwarded-For", "10.0.0.201")
                        .build());
        filter.filter(exchangeB, chain).block();

        assertThat(exchangeB.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void xForwardedFor_firstIpUsed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void order_isNegativeTwo() {
        assertThat(filter.getOrder()).isEqualTo(-2);
    }
}
