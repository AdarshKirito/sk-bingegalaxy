package com.skbingegalaxy.gateway.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed token-bucket rate limiter with three-tier per-IP design:
 * <ul>
 *   <li><b>Sensitive credential-recovery</b> (forgot/reset/verify-email): 5 req/min/IP —
 *       curbs account-enumeration and reset spam.</li>
 *   <li><b>Credential-presenting auth</b> (login, register, OAuth exchange, OTP verify,
 *       change-password): 30 req/min/IP — caps brute-force attempts on credentials.</li>
 *   <li><b>Standard API</b> (everything else, including authenticated session endpoints
 *       such as {@code /logout}, {@code /refresh}, {@code /profile}, admin reads, and all
 *       business APIs): 100 req/min/IP.</li>
 * </ul>
 * <p>
 * Industry practice (Google, AWS, GitHub, Auth0) is to throttle only credential-presenting
 * endpoints aggressively per-IP and to mitigate brute-force per-account post-authentication.
 * Logout, refresh, and profile reads are deliberately NOT bucketed with login so that a
 * legitimate customer / admin / super-admin who logs in and out repeatedly (or whose UI
 * fans out a few authenticated GETs per page) does not trip 429s.
 * <p>
 * Primary: Redis-backed sliding-window counters (distributed across gateway replicas).
 * Fallback: local LRU cache (max 10K entries) when Redis is unavailable.
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final int MAX_BUCKETS = 10_000;
    // Default per-IP per-minute caps. Production values; can be overridden
    // per-environment via:
    //   APP_RATELIMIT_STANDARD   (default 100 — business APIs)
    //   APP_RATELIMIT_AUTH       (default 30  — login / register / OAuth / OTP)
    //   APP_RATELIMIT_SENSITIVE  (default 5   — forgot / reset / verify-email)
    // Local-dev / k6 load runs raise the standard cap (see docker-compose.yml).
    private static final int DEFAULT_STANDARD_RATE_LIMIT = 100;
    private static final int DEFAULT_AUTH_RATE_LIMIT = 30;
    private static final int DEFAULT_SENSITIVE_AUTH_RATE_LIMIT = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final Duration REDIS_COUNTER_TTL = RATE_LIMIT_WINDOW.plusSeconds(5);
    private static final long REDIS_FALLBACK_LOG_WINDOW_MS = 60_000;
    private static final String RETRY_AFTER_SECONDS = "60";

    private final int standardRateLimit;
    private final int authRateLimit;
    private final int sensitiveAuthRateLimit;

    @SuppressWarnings("serial")
    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_BUCKETS;
            }
        });

    private final AtomicLong lastRedisFallbackLogAt = new AtomicLong(0);

    private ReactiveStringRedisTemplate redisTemplate;

    public RateLimitFilter() {
        this(null,
            DEFAULT_STANDARD_RATE_LIMIT,
            DEFAULT_AUTH_RATE_LIMIT,
            DEFAULT_SENSITIVE_AUTH_RATE_LIMIT);
    }

    @Autowired
    public RateLimitFilter(@Autowired(required = false) ReactiveStringRedisTemplate redisTemplate,
                           @Value("${app.ratelimit.standard:" + DEFAULT_STANDARD_RATE_LIMIT + "}") int standardRateLimit,
                           @Value("${app.ratelimit.auth:" + DEFAULT_AUTH_RATE_LIMIT + "}") int authRateLimit,
                           @Value("${app.ratelimit.sensitive:" + DEFAULT_SENSITIVE_AUTH_RATE_LIMIT + "}") int sensitiveAuthRateLimit) {
        this.redisTemplate = redisTemplate;
        this.standardRateLimit = standardRateLimit;
        this.authRateLimit = authRateLimit;
        this.sensitiveAuthRateLimit = sensitiveAuthRateLimit;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = resolveClientIp(exchange);
        String path = exchange.getRequest().getURI().getPath();
        boolean isSensitiveAuth = isSensitiveAuthPath(path);
        boolean isCredentialAuth = !isSensitiveAuth && isCredentialAuthPath(path);
        String bucketKey;
        int limit;
        if (isSensitiveAuth) {
            bucketKey = clientIp + ":auth-sensitive";
            limit = sensitiveAuthRateLimit;
        } else if (isCredentialAuth) {
            bucketKey = clientIp + ":auth";
            limit = authRateLimit;
        } else {
            bucketKey = clientIp;
            limit = standardRateLimit;
        }

        if (redisTemplate != null) {
            return applyRedisRateLimit(exchange, chain, bucketKey, limit, path)
                .onErrorResume(ex -> {
                    logRedisFallback(ex);
                    return applyLocalRateLimit(exchange, chain, bucketKey, limit, path);
                });
        }

        return applyLocalRateLimit(exchange, chain, bucketKey, limit, path);
    }

    @Override
    public int getOrder() {
        return -2;
    }

    /**
     * Credential-recovery endpoints: very tight cap to throttle enumeration / reset spam.
     */
    private boolean isSensitiveAuthPath(String path) {
        if (!path.startsWith("/api/v1/auth/")) return false;
        return path.contains("/forgot-password")
            || path.contains("/reset-password")
            || path.contains("/verify-email");
    }

    /**
     * Endpoints that accept credentials (password, OAuth code, OTP) or create accounts.
     * These are the only auth routes that warrant the strict per-IP brute-force cap.
     * Session-bearing endpoints (logout, refresh, profile, admin reads) are intentionally
     * NOT included so normal usage by customer / admin / super-admin doesn't trip 429.
     */
    private boolean isCredentialAuthPath(String path) {
        if (!path.startsWith("/api/v1/auth/")) return false;
        return path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/auth/admin/login")
            || path.equals("/api/v1/auth/register")
            || path.equals("/api/v1/auth/admin/register")
            || path.equals("/api/v1/auth/google")
            || path.equals("/api/v1/auth/verify-otp")
            || path.equals("/api/v1/auth/change-password")
            || path.equals("/api/v1/auth/change-email");
    }

    private Mono<Void> applyRedisRateLimit(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           String bucketKey,
                                           int limit,
                                           String path) {
        String counterKey = resolveRedisCounterKey(bucketKey);
        return redisTemplate.opsForValue().increment(counterKey)
            .flatMap(requestCount -> {
                Mono<Boolean> ensureExpiry = requestCount == 1
                    ? redisTemplate.expire(counterKey, REDIS_COUNTER_TTL).onErrorReturn(Boolean.FALSE)
                    : Mono.just(Boolean.TRUE);

                return ensureExpiry.then(
                    requestCount <= limit
                        ? chain.filter(exchange)
                        : rejectRequest(exchange, path, bucketKey));
            });
    }

    private Mono<Void> applyLocalRateLimit(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           String bucketKey,
                                           int limit,
                                           String path) {
        Bucket bucket = buckets.computeIfAbsent(bucketKey, key -> createBucket(limit));
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }

        return rejectRequest(exchange, path, bucketKey);
    }

    private Bucket createBucket(int limit) {
        Bandwidth bandwidth = Bandwidth.classic(limit, Refill.intervally(limit, RATE_LIMIT_WINDOW));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private Mono<Void> rejectRequest(ServerWebExchange exchange, String path, String bucketKey) {
        log.warn("Rate limit exceeded for {} on path {}", bucketKey, path);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Retry-After", RETRY_AFTER_SECONDS);
        response.getHeaders().add("X-RateLimit-Retry-After", RETRY_AFTER_SECONDS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        long retrySecs = Long.parseLong(RETRY_AFTER_SECONDS);
        String body = "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"You're sending requests too quickly. Please wait "
                + retrySecs + " second" + (retrySecs != 1 ? "s" : "") + " before trying again.\",\"retryAfterSeconds\":"
                + retrySecs + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String resolveRedisCounterKey(String bucketKey) {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        return "rate-limit:" + bucketKey + ':' + currentMinute;
    }

    private void logRedisFallback(Throwable ex) {
        long now = System.currentTimeMillis();
        long lastLoggedAt = lastRedisFallbackLogAt.get();
        if (now - lastLoggedAt >= REDIS_FALLBACK_LOG_WINDOW_MS
            && lastRedisFallbackLogAt.compareAndSet(lastLoggedAt, now)) {
            log.warn("Redis-backed rate limiting unavailable, falling back to local buckets: {}", ex.getMessage());
        }
    }

    /**
     * Resolve client IP for rate-limiting purposes.
     * Only trusts proxy headers when behind a known reverse proxy (K8s Ingress or Nginx).
     * In that topology the LAST value in X-Forwarded-For (appended by our proxy) is trustworthy.
     * When no proxy is present we always use the direct connection address.
     */
    private String resolveClientIp(ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        String remoteIp = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";

        // Trust proxy-set headers only when the direct peer is a private/loopback address,
        // i.e., the request came through our own infrastructure proxy (Nginx Ingress, etc.)
        if (isPrivateOrLoopback(remoteIp)) {
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // The *last* entry is appended by our trusted proxy; earlier entries are client-supplied
                String[] parts = forwardedFor.split(",");
                String proxyAppended = normalizeIp(parts[parts.length - 1]);
                if (proxyAppended != null) return proxyAppended;
            }

            String realIp = normalizeIp(exchange.getRequest().getHeaders().getFirst("X-Real-IP"));
            if (realIp != null) return realIp;
        }

        return remoteIp;
    }

    private boolean isPrivateOrLoopback(String ip) {
        if (ip == null || "unknown".equalsIgnoreCase(ip)) return false;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private String normalizeIp(String candidate) {
        if (candidate == null) {
            return null;
        }
        String value = candidate.trim();
        if (value.isEmpty() || "unknown".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']'));
        } else if (value.contains(":") && value.indexOf(':') == value.lastIndexOf(':') && value.contains(".")) {
            value = value.substring(0, value.indexOf(':'));
        }
        return value;
    }
}
