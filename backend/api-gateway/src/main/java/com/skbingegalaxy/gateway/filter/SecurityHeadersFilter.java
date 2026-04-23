package com.skbingegalaxy.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Injects the OWASP-recommended baseline of HTTP security response headers
 * on every response that leaves the gateway. Runs <em>before</em> the chain
 * so downstream filters (and even error handlers) cannot accidentally
 * strip the headers.
 *
 * <p>Headers set:</p>
 * <ul>
 *   <li>{@code Strict-Transport-Security} — force HTTPS for 1 year, include
 *       subdomains and pre-load. Only meaningful on TLS — ingress terminates
 *       TLS so this is safe.</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — stop MIME sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — clickjacking protection. Frontend
 *       is never embedded; blanket DENY is correct.</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — don't
 *       leak paths to third-party sites.</li>
 *   <li>{@code Permissions-Policy} — deny sensors / camera / mic / payment
 *       APIs we don't use, reducing attack surface for injected scripts.</li>
 *   <li>{@code Content-Security-Policy} — baseline locked to self; the
 *       frontend explicitly allows its CDN/analytics origins via its own
 *       meta tag, so this is a safety net for API responses (which should
 *       never render HTML anyway).</li>
 *   <li>{@code Cross-Origin-Opener-Policy}, {@code Cross-Origin-Resource-Policy},
 *       {@code Cross-Origin-Embedder-Policy} — isolate the browsing context.</li>
 * </ul>
 *
 * <p>These are sent on every response, including 4xx / 5xx / CORS preflight.
 * Setting them here (not at ingress) means they are portable across any
 * ingress controller and survive a misconfigured edge load-balancer.</p>
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    // 1 year; required for HSTS preload submission.
    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains; preload";
    private static final String CSP_VALUE =
        "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final String PERMISSIONS_POLICY =
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
      + "magnetometer=(), microphone=(), payment=(), usb=()";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Attach a commit hook so headers land regardless of downstream flow.
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            putIfAbsent(headers, "Strict-Transport-Security", HSTS_VALUE);
            putIfAbsent(headers, "X-Content-Type-Options", "nosniff");
            putIfAbsent(headers, "X-Frame-Options", "DENY");
            putIfAbsent(headers, "Referrer-Policy", "strict-origin-when-cross-origin");
            putIfAbsent(headers, "Permissions-Policy", PERMISSIONS_POLICY);
            putIfAbsent(headers, "Content-Security-Policy", CSP_VALUE);
            putIfAbsent(headers, "Cross-Origin-Opener-Policy", "same-origin");
            putIfAbsent(headers, "Cross-Origin-Resource-Policy", "same-origin");
            // Legacy but still recommended for defence-in-depth against old UAs.
            putIfAbsent(headers, "X-XSS-Protection", "0");
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private static void putIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        // Run very early so even short-circuited responses (rate-limit 429,
        // auth 401) carry the headers. Lower than RateLimitFilter (-2) so
        // this commit hook is attached first.
        return -100;
    }
}
