package com.skbingegalaxy.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;

@Slf4j
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of(
        "CUSTOMER", "ADMIN", "SUPER_ADMIN");

    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/google",
        "/api/v1/auth/admin/login",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password",
        "/api/v1/auth/verify-otp",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/availability/dates",
        "/api/v1/availability/slots",
        "/api/v1/availability/event-types",
        "/api/v1/bookings/binges",
        "/api/v1/bookings/event-types",
        "/api/v1/bookings/add-ons",
        "/api/v1/bookings/booked-slots",
        // Funnel analytics ingest — guests can step through the wizard, so we
        // accept anonymous metric pings. Server-side dedup is not needed: the
        // counter is incremented per request and the cardinality is bounded
        // by a fixed enum of stage names (Item 27).
        "/api/v1/bookings/analytics/funnel",
        "/api/v1/payments/callback",
        // Razorpay dispute/chargeback webhooks — called directly by Razorpay with no JWT.
        // Security is enforced by HMAC-SHA256 signature verification in DisputeWebhookService,
        // not by JWT auth. Must be public so Razorpay's servers can reach this endpoint.
        "/api/v1/payments/webhooks/",
        "/api/v1/site-content/public",
        // Booking-transfer recipient endpoints — token IS the bearer (magic-link
        // pattern). Recipient may not yet have an account; the token proves the
        // sender targeted that exact email address.
        "/api/v1/booking-transfers/by-token/",
        "/actuator/health"
    );

    @Value("${spring.security.jwt.secret}")
    private String jwtSecret;

    /**
     * Expected issuer ({@code iss}) claim. Tokens carrying a different issuer are rejected.
     * Auth-service stamps new tokens with the same value. Missing iss is tolerated so that
     * tokens minted before iss/aud rollout keep working until they expire.
     */
    @Value("${spring.security.jwt.issuer:skbingegalaxy-auth}")
    private String jwtIssuer;

    /**
     * Expected audience ({@code aud}) claim. Same backward-compat rule as {@link #jwtIssuer}.
     */
    @Value("${spring.security.jwt.audience:skbingegalaxy-web}")
    private String jwtAudience;

    @jakarta.annotation.PostConstruct
    void validateConfig() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured (spring.security.jwt.secret)");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // SECURITY: Always strip X-User-* headers from incoming requests to prevent spoofing.
        // The gateway is the only source of truth for these headers.
        // Log a WARN line if any client-supplied identity header was present so that
        // SOC tooling can detect spoofing attempts (count + alert in monitoring).
        HttpHeaders inbound = request.getHeaders();
        if (inbound.containsKey("X-User-Id") || inbound.containsKey("X-User-Role")
                || inbound.containsKey("X-User-Email") || inbound.containsKey("X-User-Name")
                || inbound.containsKey("X-User-Phone") || inbound.containsKey("X-User-Phone-Country-Code")
                || inbound.containsKey("X-Authority-Delegated") || inbound.containsKey("X-Authority-Scope")
                || inbound.containsKey("X-Authority-Native-Role")) {
            log.warn("auth.header.spoof.attempt path={} remote={} ua={}",
                path,
                request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown",
                inbound.getFirst(HttpHeaders.USER_AGENT));
        }
        ServerHttpRequest stripped = request.mutate()
            .headers(h -> {
                h.remove("X-User-Id");
                h.remove("X-User-Email");
                h.remove("X-User-Role");
                h.remove("X-User-Name");
                h.remove("X-User-Phone");
                h.remove("X-User-Phone-Country-Code");
                // Authority Handover headers are stamped by THIS filter on
                // delegated requests. Strip any client-supplied values so an
                // external caller cannot forge a delegation context (which
                // would let them trip the lock-enforcer for self-DoS, or
                // worse, mislead audit logging).
                h.remove("X-Authority-Delegated");
                h.remove("X-Authority-Scope");
                h.remove("X-Authority-Native-Role");
            })
            .build();
        exchange = exchange.mutate().request(stripped).build();
        request = stripped;

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // Try Authorization header first, then fall back to httpOnly cookie
        String token = extractToken(request);
        if (token == null) {
            log.warn("No JWT token found for secured path {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            Claims claims = validateToken(token);
            String role = claims.get("role", String.class);

            // Validate the role claim is a known role
            if (role == null || !VALID_ROLES.contains(role)) {
                log.warn("Invalid role '{}' in JWT for path {}", role, path);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            if (isSuperAdminPath(path) && !"SUPER_ADMIN".equals(role)) {
                // Defer hard reject until after we've evaluated Authority Handover
                // delegation claims below — a delegated admin with the matching scope
                // is also entitled to access the path. We still pre-reject CUSTOMER
                // tokens (they have no possible delegation path) for cheap fail-fast.
                if ("CUSTOMER".equals(role)) {
                    log.warn("Customer role accessing super-admin path {}", path);
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
            }

            if (isAdminPath(path) && !"ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                log.warn("Non-admin role '{}' accessing admin path {}", role, path);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            String firstName = claims.get("firstName", String.class);
            String phone = claims.get("phone", String.class);
            String phoneCountryCode = claims.get("phoneCountryCode", String.class);
            String subject = claims.getSubject();
            String email = claims.get("email", String.class);

            if (subject == null || subject.isBlank()) {
                log.warn("JWT missing subject claim for path {}", path);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // ── Authority Handover: derive effective role from delegation claims ──
            // The native role claim is never modified inside the JWT — that keeps the
            // token truthful. Instead the gateway, which already owns the X-User-Role
            // contract for downstream services, elevates the role on a per-path basis
            // when delegation grants the matching scope.
            String effectiveRole = role;
            String matchedScope = null;
            boolean delegated = false;
            String delegatedScopesClaim = claims.get("delegatedScopes", String.class);
            if (delegatedScopesClaim != null && !delegatedScopesClaim.isBlank() && "ADMIN".equals(role)) {
                Long expiresAt = claims.get("delegationExpiresAt", Long.class);
                long nowMs = System.currentTimeMillis();
                if (expiresAt != null && expiresAt < nowMs) {
                    log.debug("Delegation expired (expiresAt={} now={}); ignoring scopes", expiresAt, nowMs);
                } else {
                    java.util.Set<String> scopes = new java.util.HashSet<>(
                        java.util.Arrays.asList(delegatedScopesClaim.split(",")));
                    String required = scopeRequiredFor(path);
                    if (required != null && scopes.contains(required)) {
                        effectiveRole = "SUPER_ADMIN";
                        matchedScope = required;
                        delegated = true;
                        log.info("authority.delegation.elevate userId={} scope={} path={}",
                            subject, required, path);
                    }
                }
            }

            // Re-run super-admin gate against the *effective* role (so a delegated admin
            // with the right scope passes). The earlier coarse check above only rejected
            // tokens with no possible delegation; we still re-check here against the
            // resolved role so the final decision is unambiguous.
            if (isSuperAdminPath(path) && !"SUPER_ADMIN".equals(effectiveRole)) {
                log.warn("Effective role '{}' insufficient for super-admin path {}", effectiveRole, path);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            ServerHttpRequest.Builder mutator = request.mutate()
                .header("X-User-Id", subject)
                .header("X-User-Email", email != null ? email : "")
                .header("X-User-Role", effectiveRole)
                .header("X-User-Name", firstName != null ? firstName : "Customer")
                .header("X-User-Phone", phone != null ? phone : "")
                .header("X-User-Phone-Country-Code", phoneCountryCode != null ? phoneCountryCode : "");
            if (delegated) {
                mutator.header("X-Authority-Delegated", "true");
                mutator.header("X-Authority-Scope", matchedScope);
                mutator.header("X-Authority-Native-Role", role);
            }
            ServerHttpRequest mutatedRequest = mutator.build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private String extractToken(ServerHttpRequest request) {
        // 1. Authorization: Bearer <token>
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        // 2. httpOnly cookie named "token"
        HttpCookie cookie = request.getCookies().getFirst("token");
        if (cookie != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        return null;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Returns true for any path that exposes admin/super-admin functionality so the
     * gateway can enforce role-based gating. Covers:
     *   /api/v1/{service}/admin/**
     *   /api/v2/{service}/admin/**
     *   /api/v2/{service}/super-admin/**
     * Uses normalized path to prevent path-traversal bypass (e.g., /../admin/).
     */
    private boolean isAdminPath(String path) {
        String normalized = java.net.URI.create(path).normalize().getPath();
        String[] segments = normalized.split("/");
        // ["", "api", "v1|v2", "service", "admin|super-admin", ...]
        if (segments.length < 5) return false;
        String fifth = segments[4];
        return "admin".equals(fifth) || "super-admin".equals(fifth);
    }

    /**
     * Returns true for paths that require the SUPER_ADMIN role specifically.
     * Currently the only such surface is the loyalty v2 super-admin controller, but
     * we match on the segment shape so any future {@code /api/v*\/.../super-admin/**}
     * route is automatically locked down without code changes here.
     */
    private boolean isSuperAdminPath(String path) {
        String normalized = java.net.URI.create(path).normalize().getPath();
        String[] segments = normalized.split("/");
        return segments.length >= 5 && "super-admin".equals(segments[4]);
    }

    /**
     * Authority Handover scope mapping: returns the required {@link
     * com.skbingegalaxy.common.enums.AuthorityScope} (as a string) for the given
     * request path, or {@code null} if the path is not gated by any per-page scope.
     *
     * <p>The matcher is intentionally a flat ordered list rather than a regex tree —
     * the set of super-admin pages is small (~10) and the path prefixes are stable.
     * Order matters: more specific prefixes must precede more generic ones (e.g.
     * the loyalty super-admin path before the loyalty admin path).
     *
     * <p>Native super-admins always pass without consulting this map; only delegated
     * admins (role=ADMIN with delegatedScopes claim) are constrained by it.
     */
    private static final List<java.util.Map.Entry<String, String>> SCOPE_MAP = List.of(
        // Currencies (booking-service)
        java.util.Map.entry("/api/v1/bookings/admin/currencies", "CURRENCIES"),
        // Notification templates (notification-service)
        java.util.Map.entry("/api/v1/notifications/admin/templates", "NOTIFICATIONS"),
        java.util.Map.entry("/api/v1/notifications/super-admin", "NOTIFICATIONS"),
        // Loyalty (loyalty-v2 lives under booking-service)
        java.util.Map.entry("/api/v1/bookings/super-admin/loyalty", "LOYALTY"),
        java.util.Map.entry("/api/v1/bookings/admin/loyalty", "LOYALTY"),
        java.util.Map.entry("/api/v1/loyalty/super-admin", "LOYALTY"),
        java.util.Map.entry("/api/v1/loyalty/admin", "LOYALTY"),
        // Operations (waitlist, cancellations, refunds dashboards)
        java.util.Map.entry("/api/v1/bookings/admin/ops", "OPS"),
        java.util.Map.entry("/api/v1/bookings/admin/operations", "OPS"),
        // All-users / customer admin (auth-service)
        java.util.Map.entry("/api/v1/auth/admin/customers", "CUSTOMER_EDIT"),
        java.util.Map.entry("/api/v1/auth/admin/users", "ALL_USERS"),
        java.util.Map.entry("/api/v1/auth/super-admin/users", "ALL_USERS"),
        java.util.Map.entry("/api/v1/auth/admin/register", "ADMIN_REGISTER"),
        // CMS (site-content-service via booking-service router or its own path)
        java.util.Map.entry("/api/v1/site-content/admin/account", "ACCOUNT_CMS"),
        java.util.Map.entry("/api/v1/site-content/super-admin/account", "ACCOUNT_CMS"),
        java.util.Map.entry("/api/v1/site-content/admin/home", "HOME_CMS"),
        java.util.Map.entry("/api/v1/site-content/super-admin/home", "HOME_CMS"),
        java.util.Map.entry("/api/v1/site-content/admin", "HOME_CMS"),
        // Super-admin dashboard widgets (audit log, sessions overview, etc.)
        java.util.Map.entry("/api/v1/auth/super-admin/audit", "SUPER_DASHBOARD"),
        java.util.Map.entry("/api/v1/auth/super-admin/sessions", "SUPER_DASHBOARD"),
        java.util.Map.entry("/api/v1/auth/admin/sessions", "SUPER_DASHBOARD")
    );

    private String scopeRequiredFor(String path) {
        if (path == null || path.isEmpty()) return null;
        String normalized = java.net.URI.create(path).normalize().getPath();
        for (java.util.Map.Entry<String, String> entry : SCOPE_MAP) {
            if (normalized.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Claims validateToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        // verifyWith(key) enforces HMAC signature validation, preventing alg:none attacks.
        // 30-second clock skew tolerance handles sub-second timing differences between
        // Docker containers without meaningfully extending the effective token lifetime.
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(30)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        // Soft iss/aud enforcement: only reject when the claim is present AND wrong.
        // This keeps existing unexpired tokens (minted before iss/aud rollout) valid
        // until they naturally expire. New tokens always carry both claims.
        String iss = claims.getIssuer();
        if (iss != null && !jwtIssuer.equals(iss)) {
            throw new io.jsonwebtoken.JwtException("Unexpected token issuer");
        }
        java.util.Set<String> aud = claims.getAudience();
        if (aud != null && !aud.isEmpty() && !aud.contains(jwtAudience)) {
            throw new io.jsonwebtoken.JwtException("Unexpected token audience");
        }
        return claims;
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
