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
        "/api/v1/payments/callback",
        "/api/v1/site-content/public",
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
                || inbound.containsKey("X-User-Phone") || inbound.containsKey("X-User-Phone-Country-Code")) {
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
                log.warn("Non super-admin role '{}' accessing super-admin path {}", role, path);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
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

            ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", subject)
                .header("X-User-Email", email != null ? email : "")
                .header("X-User-Role", role)
                .header("X-User-Name", firstName != null ? firstName : "Customer")
                .header("X-User-Phone", phone != null ? phone : "")
                .header("X-User-Phone-Country-Code", phoneCountryCode != null ? phoneCountryCode : "")
                .build();

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
