package com.skbingegalaxy.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSRF protection for the API gateway.
 *
 * <p><b>Strategy — double-submit cookie + Origin/Referer pinning</b>:
 * <ol>
 *   <li>The SPA fetches {@code GET /api/v1/csrf} (served by
 *       {@link com.skbingegalaxy.gateway.controller.CsrfTokenController})
 *       which issues an opaque {@code XSRF-TOKEN} cookie (non-httpOnly,
 *       {@code SameSite=Strict}, {@code Secure} in production).</li>
 *   <li>For every state-changing request ({@code POST/PUT/PATCH/DELETE})
 *       the SPA echoes the cookie value in the {@code X-XSRF-TOKEN}
 *       header. Because of {@code SameSite=Strict}, an attacker on a
 *       different origin can never read the cookie.</li>
 *   <li>As defence in depth we additionally verify the {@code Origin}
 *       (or {@code Referer}) matches the configured allow-list.</li>
 * </ol>
 *
 * <p><b>Exemptions</b>:
 * <ul>
 *   <li>Safe methods ({@code GET/HEAD/OPTIONS}) — no state change.</li>
 *   <li>Webhooks at {@code /api/v1/payments/callback} — authenticated via
 *       HMAC-SHA256 signature, server-to-server.</li>
 *   <li>Auth bootstrap endpoints (login/register/refresh/google/forgot/
 *       reset/verify-otp) — at this point the SPA has no XSRF cookie.
 *       Origin pinning + rate limiting + per-account lockout protect
 *       these.</li>
 * </ul>
 *
 * <p><b>Order</b>: {@code -3} — runs before
 * {@link RateLimitFilter} ({@code -2}) and {@link JwtAuthenticationFilter}
 * ({@code -1}) so forged cross-site requests are rejected before any
 * authentication or rate-limit work.
 */
@Slf4j
@Component
public class CsrfProtectionFilter implements GlobalFilter, Ordered {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final Set<String> BOOTSTRAP_AUTH_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/admin/login",
        "/api/v1/auth/google",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password",
        "/api/v1/auth/verify-otp",
        "/api/v1/auth/refresh"
    );

    private static final String WEBHOOK_PATH = "/api/v1/payments/callback";

    /**
     * Public POST endpoints that are intentionally callable by anonymous
     * clients (incl. {@code navigator.sendBeacon} / {@code credentials: 'omit'}
     * fetches that cannot carry the {@code XSRF-TOKEN} cookie).
     *
     * <p>These endpoints are still protected by:
     * <ul>
     *   <li>Origin / Referer pinning (enforced above),</li>
     *   <li>Per-IP rate limiting in {@link RateLimitFilter},</li>
     *   <li>Strict server-side validation and idempotent semantics —
     *       e.g. funnel ingestion is fire-and-forget metrics with no
     *       privileged effect on user state.</li>
     * </ul>
     *
     * <p>Add to this set ONLY for endpoints that are explicitly designed
     * to be guest-callable and whose effects are constrained to
     * non-privileged, low-value telemetry.
     */
    private static final Set<String> ANONYMOUS_POST_PATHS = Set.of(
        // Booking-wizard funnel telemetry — fired by guests via sendBeacon
        // with credentials:'omit'; cannot carry an XSRF cookie by design.
        "/api/v1/bookings/analytics/funnel"
    );

    private static final Set<HttpMethod> STATE_CHANGING = Set.of(
        HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE
    );

    private final List<String> allowedOrigins;
    private final boolean cookieSecure;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Counter csrfRejectCounter;
    private final SecureRandom secureRandom = new SecureRandom();

    public CsrfProtectionFilter(@Value("${app.cors.allowed-origins:}") List<String> allowedOrigins,
                                @Value("${app.cookie.secure:true}") boolean cookieSecure,
                                @Autowired(required = false) MeterRegistry meterRegistry) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        this.cookieSecure = cookieSecure;
        this.csrfRejectCounter = meterRegistry == null
            ? null
            : Counter.builder("skbg_gateway_csrf_rejected_total")
                .description("Number of state-changing requests rejected by the gateway CSRF filter.")
                .register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // Safe methods: piggy-back the cookie on the response so the SPA can
        // pick it up on its first GET without an explicit /api/v1/csrf call.
        if (!STATE_CHANGING.contains(method)) {
            ensureTokenCookie(exchange);
            return chain.filter(exchange);
        }

        // State-changing requests below.
        if (WEBHOOK_PATH.equals(path)) {
            return chain.filter(exchange);
        }

        if (!isOriginAllowed(request.getHeaders())) {
            return reject(exchange, "CSRF_BAD_ORIGIN",
                "Cross-site request blocked: invalid Origin / Referer.", path);
        }

        if (BOOTSTRAP_AUTH_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        // Anonymous-by-design public POST endpoints (Origin pinning above
        // is the primary CSRF defence here — these endpoints cannot carry
        // the XSRF cookie because they are called with credentials:'omit'
        // / sendBeacon by guest browsers).
        if (ANONYMOUS_POST_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        HttpCookie cookieToken = request.getCookies().getFirst(CSRF_COOKIE);
        String headerToken = request.getHeaders().getFirst(CSRF_HEADER);

        if (cookieToken == null || cookieToken.getValue().isBlank()
                || headerToken == null || headerToken.isBlank()) {
            return reject(exchange, "CSRF_TOKEN_MISSING",
                "CSRF token missing. Call GET /api/v1/csrf to obtain one.", path);
        }
        if (!constantTimeEquals(cookieToken.getValue(), headerToken)) {
            return reject(exchange, "CSRF_TOKEN_MISMATCH",
                "CSRF token mismatch.", path);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -3;
    }

    public void ensureTokenCookie(ServerWebExchange exchange) {
        if (exchange.getRequest().getCookies().getFirst(CSRF_COOKIE) != null) {
            return;
        }
        exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, buildCookie(mintToken()));
    }

    public String mintToken() {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public String buildCookie(String token) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSRF_COOKIE).append('=').append(token);
        sb.append("; Path=/");
        sb.append("; Max-Age=").append(60 * 60 * 8); // 8h, refreshed on each safe call
        sb.append("; SameSite=Strict");
        if (cookieSecure) sb.append("; Secure");
        // Intentionally NOT HttpOnly — the SPA must read this cookie.
        return sb.toString();
    }

    boolean isOriginAllowed(HttpHeaders headers) {
        String origin = headers.getOrigin();
        if (origin != null && !origin.isBlank()) {
            return matchesAllowList(origin);
        }
        String referer = headers.getFirst(HttpHeaders.REFERER);
        if (referer != null && !referer.isBlank()) {
            try {
                URI ref = new URI(referer);
                String refOrigin = ref.getScheme() + "://" + ref.getAuthority();
                return matchesAllowList(refOrigin);
            } catch (URISyntaxException e) {
                return false;
            }
        }
        return false;
    }

    private boolean matchesAllowList(String origin) {
        for (String allowed : allowedOrigins) {
            if (allowed != null && allowed.trim().equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String code, String message, String path) {
        if (csrfRejectCounter != null) {
            csrfRejectCounter.increment();
        }
        log.warn("csrf.reject code={} path={} ip={} origin={} referer={}",
            code, path,
            exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown",
            exchange.getRequest().getHeaders().getOrigin(),
            exchange.getRequest().getHeaders().getFirst(HttpHeaders.REFERER));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", code);
        body.put("retryable", false);
        body.put("message", message);
        body.put("status", 403);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }
}
