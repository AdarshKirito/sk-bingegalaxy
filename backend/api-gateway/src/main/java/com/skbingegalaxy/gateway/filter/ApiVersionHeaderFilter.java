package com.skbingegalaxy.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds API versioning response headers:
 * - X-API-Version: current version being served
 * - X-API-Deprecated / Sunset: when a version is deprecated
 *
 * Future-proofing: when /api/v2/ is introduced, requests to /api/v1/ will
 * carry deprecation headers so clients can migrate gracefully.
 */
@Component
public class ApiVersionHeaderFilter implements GlobalFilter, Ordered {

    private static final String CURRENT_VERSION = "v1";
    // Set to true + date when v1 is being sunset
    private static final boolean V1_DEPRECATED = false;
    private static final String V1_SUNSET_DATE = ""; // RFC 7231 date, e.g. "Sat, 01 Mar 2025 00:00:00 GMT"

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            String path = exchange.getRequest().getURI().getPath();

            if (path.startsWith("/api/v1/")) {
                response.getHeaders().add("X-API-Version", CURRENT_VERSION);

                if (V1_DEPRECATED) {
                    response.getHeaders().add("Deprecation", "true");
                    if (!V1_SUNSET_DATE.isEmpty()) {
                        response.getHeaders().add("Sunset", V1_SUNSET_DATE);
                    }
                }
            }
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
