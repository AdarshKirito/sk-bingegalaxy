package com.skbingegalaxy.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    /** Handle ALL HTTP methods — circuit breaker forwards any method here. */
    @RequestMapping("/fallback")
    public Mono<Map<String, Object>> fallback(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
            "success", false,
            "message", "Service is temporarily unavailable. Please try again later.",
            "status", 503
        ));
    }
}
