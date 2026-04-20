package com.skbingegalaxy.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Propagates userId and bingeId into MDC for every request flowing through
 * the gateway, so structured JSON logs include these fields.
 * <p>
 * In a reactive stack, MDC is ThreadLocal-based and unreliable across
 * async boundaries. This filter uses Reactor Context + contextWrite to
 * ensure MDC is populated on each thread switch.
 */
@Component
public class MdcContextFilter implements GlobalFilter, Ordered {

    private static final String MDC_USER_ID = "userId";
    private static final String MDC_BINGE_ID = "bingeId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String userId = request.getHeaders().getFirst("X-User-Id");
        String bingeId = request.getHeaders().getFirst("X-Binge-Id");
        if (bingeId == null) {
            bingeId = request.getQueryParams().getFirst("bingeId");
        }

        // Snapshot values for the closure
        final String uid = userId;
        final String bid = bingeId;

        return chain.filter(exchange)
                .contextWrite(ctx -> {
                    Context c = ctx;
                    if (uid != null && !uid.isBlank()) {
                        c = c.put(MDC_USER_ID, uid);
                    }
                    if (bid != null && !bid.isBlank()) {
                        c = c.put(MDC_BINGE_ID, bid);
                    }
                    return c;
                })
                .doFirst(() -> {
                    if (uid != null && !uid.isBlank()) MDC.put(MDC_USER_ID, uid);
                    if (bid != null && !bid.isBlank()) MDC.put(MDC_BINGE_ID, bid);
                })
                .doFinally(signal -> {
                    MDC.remove(MDC_USER_ID);
                    MDC.remove(MDC_BINGE_ID);
                });
    }

    @Override
    public int getOrder() {
        // Run after JwtAuthenticationFilter (order -1) so X-User-Id is already set
        return 0;
    }
}
