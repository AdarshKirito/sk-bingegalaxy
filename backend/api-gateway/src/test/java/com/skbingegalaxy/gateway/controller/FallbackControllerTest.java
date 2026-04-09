package com.skbingegalaxy.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void fallback_returns503WithErrorBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/fallback").build());

        Map<String, Object> body = controller.fallback(exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("status", 503);
        assertThat(body).containsKey("message");
    }
}
