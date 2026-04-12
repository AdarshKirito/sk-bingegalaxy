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
        "/actuator/health"
    );

    @Value("${spring.security.jwt.secret}")
    private String jwtSecret;

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
        ServerHttpRequest stripped = request.mutate()
            .headers(h -> {
                h.remove("X-User-Id");
                h.remove("X-User-Email");
                h.remove("X-User-Role");
                h.remove("X-User-Name");
                h.remove("X-User-Phone");
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

            if (isAdminPath(path) && !"ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                log.warn("Non-admin role '{}' accessing admin path {}", role, path);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            String firstName = claims.get("firstName", String.class);
            String phone = claims.get("phone", String.class);
            ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-User-Email", claims.get("email", String.class))
                .header("X-User-Role", role)
                .header("X-User-Name", firstName != null ? firstName : "Customer")
                .header("X-User-Phone", phone != null ? phone : "")
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
            return authHeader.substring(7);
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
     * Returns true for any /api/v1/{service}/admin path segment.
     * Uses normalized path to prevent path-traversal bypass (e.g., /../admin/).
     */
    private boolean isAdminPath(String path) {
        // Normalize to prevent ../ bypass
        String normalized = java.net.URI.create(path).normalize().getPath();
        String[] segments = normalized.split("/");
        // /api/v1/xxx/admin... => ["", "api", "v1", "xxx", "admin", ...]
        return segments.length >= 5 && "admin".equals(segments[4]);
    }

    private Claims validateToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        // verifyWith(key) enforces HMAC signature validation, preventing alg:none attacks.
        // 30-second clock skew tolerance handles sub-second timing differences between
        // Docker containers without meaningfully extending the effective token lifetime.
        return Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(30)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
