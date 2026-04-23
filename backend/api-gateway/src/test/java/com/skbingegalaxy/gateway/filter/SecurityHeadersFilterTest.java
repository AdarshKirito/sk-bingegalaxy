package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("baseline security headers are attached to every response")
    void attachesAllBaselineHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/anything").build());

        filter.filter(exchange, chain).block();
        // Force commit-phase callbacks to run.
        exchange.getResponse().setComplete().block();

        HttpHeaders h = exchange.getResponse().getHeaders();
        assertThat(h.getFirst("Strict-Transport-Security"))
            .isEqualTo("max-age=31536000; includeSubDomains; preload");
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Referrer-Policy"))
            .isEqualTo("strict-origin-when-cross-origin");
        assertThat(h.getFirst("Content-Security-Policy")).contains("default-src 'self'");
        assertThat(h.getFirst("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(h.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(h.getFirst("Permissions-Policy")).contains("camera=()");
    }

    @Test
    @DisplayName("filter runs early enough to cover short-circuited responses")
    void orderIsEarly() {
        // Lower order = runs earlier. Must be < RateLimitFilter's -2 so the
        // commit hook is registered before any 429 response is sent.
        assertThat(filter.getOrder()).isLessThan(-2);
    }

    @Test
    @DisplayName("does not overwrite headers an upstream filter has already set")
    void respectsPreExistingHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/anything").build());
        exchange.getResponse().getHeaders().set("X-Frame-Options", "SAMEORIGIN");

        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
            .isEqualTo("SAMEORIGIN");
    }
}
