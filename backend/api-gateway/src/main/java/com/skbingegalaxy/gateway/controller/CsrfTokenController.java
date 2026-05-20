package com.skbingegalaxy.gateway.controller;

import com.skbingegalaxy.gateway.filter.CsrfProtectionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPA bootstrap endpoint for the CSRF double-submit cookie.
 *
 * <p>The SPA calls {@code GET /api/v1/csrf} once at startup (or after the
 * existing token has expired). The response sets the {@code XSRF-TOKEN}
 * cookie and also returns the token in the JSON body so the SPA can cache
 * it in memory if it prefers to read it that way.
 *
 * <p>This controller is served by the gateway itself and bypasses
 * Spring Cloud Gateway's route table (no route is configured for
 * {@code /api/v1/csrf}). The {@link CsrfProtectionFilter} therefore does
 * not run for this path — but that's intentional: the request is
 * idempotent ({@code GET}) and does not mutate state.
 */
@RestController
@RequestMapping("/api/v1/csrf")
@RequiredArgsConstructor
public class CsrfTokenController {

    private final CsrfProtectionFilter csrfFilter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> issue(ServerWebExchange exchange) {
        String token = csrfFilter.mintToken();
        exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, csrfFilter.buildCookie(token));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("token", token);
        return Mono.just(body);
    }
}
