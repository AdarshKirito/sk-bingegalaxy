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

    private static final String MDC_USER_ID     = "userId";
    private static final String MDC_BINGE_ID    = "bingeId";
    private static final String MDC_SENTRY_TRACE = "sentryTraceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String userId      = request.getHeaders().getFirst("X-User-Id");
        String bingeId     = request.getHeaders().getFirst("X-Binge-Id");
        // X-Sentry-Trace-Id is attached by the frontend api.js interceptor from the
        // active Sentry span. Propagating it here into MDC + downstream request headers
        // lets ops correlate a Sentry error to the backend Zipkin trace without manual
        // log search — both systems show the same identifier.
        String sentryTrace = request.getHeaders().getFirst("X-Sentry-Trace-Id");
        if (bingeId == null) {
            bingeId = request.getQueryParams().getFirst("bingeId");
        }

        // Forward the Sentry trace ID to downstream services so it appears in their logs.
        // Use headers(Consumer) with set() — not header() which appends — so downstream
        // services always see exactly one value regardless of what the client sent.
        ServerWebExchange mutated = sentryTrace != null && !sentryTrace.isBlank()
            ? exchange.mutate().request(
                request.mutate()
                    .headers(h -> h.set("X-Sentry-Trace-Id", sentryTrace))
                    .build()
              ).build()
            : exchange;

        // Snapshot values for the closure
        final String uid   = userId;
        final String bid   = bingeId;
        final String stid  = sentryTrace;

        return chain.filter(mutated)
                .contextWrite(ctx -> {
                    Context c = ctx;
                    if (uid  != null && !uid.isBlank())  c = c.put(MDC_USER_ID,      uid);
                    if (bid  != null && !bid.isBlank())  c = c.put(MDC_BINGE_ID,     bid);
                    if (stid != null && !stid.isBlank()) c = c.put(MDC_SENTRY_TRACE, stid);
                    return c;
                })
                .doFirst(() -> {
                    if (uid  != null && !uid.isBlank())  MDC.put(MDC_USER_ID,      uid);
                    if (bid  != null && !bid.isBlank())  MDC.put(MDC_BINGE_ID,     bid);
                    if (stid != null && !stid.isBlank()) MDC.put(MDC_SENTRY_TRACE, stid);
                })
                .doFinally(signal -> {
                    MDC.remove(MDC_USER_ID);
                    MDC.remove(MDC_BINGE_ID);
                    MDC.remove(MDC_SENTRY_TRACE);
                });
    }

    @Override
    public int getOrder() {
        // Run after JwtAuthenticationFilter (-1) and UserRateLimitFilter (0) so
        // both X-User-Id and X-Binge-Id are fully populated before MDC capture.
        return 1;
    }
}
