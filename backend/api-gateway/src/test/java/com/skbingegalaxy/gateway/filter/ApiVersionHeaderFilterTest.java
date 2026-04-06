package com.skbingegalaxy.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiVersionHeaderFilterTest {

    private ApiVersionHeaderFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ApiVersionHeaderFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            // Simulate chain completing so the .then() block executes
            return Mono.empty();
        });
    }

    @Test
    void v1Path_addsVersionHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings").build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-API-Version")).isEqualTo("v1");
    }

    @Test
    void nonApiPath_noVersionHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().containsKey("X-API-Version")).isFalse();
    }

    @Test
    void order_isNearLowestPrecedence() {
        assertThat(filter.getOrder()).isGreaterThan(0);
    }
}
