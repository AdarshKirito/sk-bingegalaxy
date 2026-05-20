package com.skbingegalaxy.gateway.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user (authenticated) / per-IP (anonymous) rate limiter for a small set of
 * <strong>high-value write endpoints</strong> that are attractive abuse targets:
 * booking creation, payment initiation, password-reset requests, and OTP verification.
 * <p>
 * Runs at order {@code 0} — <em>after</em> {@link JwtAuthenticationFilter} (order {@code -1}) —
 * so that {@code X-User-Id} is already populated for authenticated requests.
 * <p>
 * Keying strategy:
 * <ul>
 *   <li>Authenticated: {@code user:<id>:<bucket-name>}</li>
 *   <li>Anonymous (e.g. forgot-password): {@code ip:<ip>:<bucket-name>}</li>
 * </ul>
 * This sits <em>on top of</em> the coarser IP-based {@link RateLimitFilter}, which remains
 * the first line of defence. Redis-first with an in-memory LRU fallback, identical to
 * {@link RateLimitFilter} so Redis hiccups degrade gracefully rather than hard-failing.
 */
@Slf4j
@Component
public class UserRateLimitFilter implements GlobalFilter, Ordered {

    private static final int MAX_BUCKETS = 10_000;
    private static final Duration REDIS_COUNTER_TTL_BUFFER = Duration.ofSeconds(5);
    private static final long REDIS_FALLBACK_LOG_WINDOW_MS = 60_000;

    /**
     * A rule that fires on exact-path + method match. Ordering matters only cosmetically;
     * the first matching rule wins per request.
     */
    private record Rule(HttpMethod method, String path, String bucketName, int limit, Duration window) {
        boolean matches(HttpMethod m, String p) {
            return method.equals(m) && path.equals(p);
        }
    }

    /**
     * Rule set:
     * <ul>
     *   <li><b>booking-create</b> 10 / min — block booking-flood / seat-hoarding scripts.</li>
     *   <li><b>payment-initiate</b> 15 / min — allow legitimate retries, block gateway-probing.</li>
     *   <li><b>forgot-password</b> 5 / 15 min — keyed on IP (anonymous); blocks enumeration / spam.</li>
     *   <li><b>verify-otp</b> 10 / 15 min — slows OTP brute-force across reset & login flows.</li>
     * </ul>
     */
    private static final List<Rule> RULES = List.of(
        new Rule(HttpMethod.POST, "/api/v1/bookings",             "booking-create",   10, Duration.ofMinutes(1)),
        new Rule(HttpMethod.POST, "/api/v1/payments/initiate",    "payment-initiate", 15, Duration.ofMinutes(1)),
        new Rule(HttpMethod.POST, "/api/v1/auth/forgot-password", "forgot-password",   5, Duration.ofMinutes(15)),
        new Rule(HttpMethod.POST, "/api/v1/auth/verify-otp",      "verify-otp",       10, Duration.ofMinutes(15))
    );

    @SuppressWarnings("serial")
    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_BUCKETS;
            }
        });

    private final AtomicLong lastRedisFallbackLogAt = new AtomicLong(0);

    private final ReactiveStringRedisTemplate redisTemplate;

    public UserRateLimitFilter() {
        this(null);
    }

    @Autowired
    public UserRateLimitFilter(@Autowired(required = false) ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath();

        Rule rule = matchRule(method, path);
        if (rule == null) {
            return chain.filter(exchange);
        }

        String principal = resolvePrincipal(exchange);
        // Scope booking and payment buckets per binge (X-Binge-Id is sent by the frontend
        // Axios interceptor on every request) so a customer hitting the limit at Binge A is
        // never blocked from booking or paying at Binge B — the same pattern used by
        // Airbnb, Booking.com and other multi-venue platforms.
        boolean isBingeScoped = "booking-create".equals(rule.bucketName())
                || "payment-initiate".equals(rule.bucketName());
        String sanitizedBingeId = isBingeScoped
                ? sanitizeBingeId(exchange.getRequest().getHeaders().getFirst("X-Binge-Id")) : null;
        String bucketKey = principal + ":" + rule.bucketName()
                + (sanitizedBingeId != null ? ":binge-" + sanitizedBingeId : "");

        if (redisTemplate != null) {
            return applyRedisRateLimit(exchange, chain, bucketKey, rule, path)
                .onErrorResume(ex -> {
                    logRedisFallback(ex);
                    return applyLocalRateLimit(exchange, chain, bucketKey, rule, path);
                });
        }

        return applyLocalRateLimit(exchange, chain, bucketKey, rule, path);
    }

    @Override
    public int getOrder() {
        // After JwtAuthenticationFilter (-1) so X-User-Id is populated.
        return 0;
    }

    private Rule matchRule(HttpMethod method, String path) {
        if (method == null) return null;
        for (Rule r : RULES) {
            if (r.matches(method, path)) return r;
        }
        return null;
    }

    /**
     * Prefer the authenticated user id (set by {@link JwtAuthenticationFilter}) so a single
     * user behind CGNAT doesn't share a bucket with unrelated users on the same IP.
     * Fall back to client IP for anonymous endpoints (forgot-password, verify-otp pre-login).
     */
    private String resolvePrincipal(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        String ip = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
        return "ip:" + ip;
    }

    private Mono<Void> applyRedisRateLimit(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           String bucketKey,
                                           Rule rule,
                                           String path) {
        String counterKey = resolveRedisCounterKey(bucketKey, rule.window());
        return redisTemplate.opsForValue().increment(counterKey)
            .flatMap(requestCount -> {
                Mono<Boolean> ensureExpiry = requestCount == 1
                    ? redisTemplate.expire(counterKey, rule.window().plus(REDIS_COUNTER_TTL_BUFFER))
                        .onErrorReturn(Boolean.FALSE)
                    : Mono.just(Boolean.TRUE);

                return ensureExpiry.then(
                    requestCount <= rule.limit()
                        ? chain.filter(exchange)
                        : rejectRequest(exchange, path, bucketKey, rule));
            });
    }

    private Mono<Void> applyLocalRateLimit(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           String bucketKey,
                                           Rule rule,
                                           String path) {
        Bucket bucket = buckets.computeIfAbsent(bucketKey, key -> createBucket(rule));
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }
        return rejectRequest(exchange, path, bucketKey, rule);
    }

    private Bucket createBucket(Rule rule) {
        Bandwidth bandwidth = Bandwidth.classic(rule.limit(), Refill.intervally(rule.limit(), rule.window()));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private Mono<Void> rejectRequest(ServerWebExchange exchange, String path, String bucketKey, Rule rule) {
        log.warn("User rate limit exceeded bucket={} rule={} path={}", bucketKey, rule.bucketName(), path);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfterSecs = rule.window().toSeconds();
        // Compute the exact epoch-second at which the current sliding window resets —
        // consistent with resolveRedisCounterKey() so clients can back off precisely.
        long resetEpoch = (Instant.now().getEpochSecond() / retryAfterSecs + 1) * retryAfterSecs;
        response.getHeaders().add("Retry-After",          String.valueOf(retryAfterSecs));
        response.getHeaders().add("X-RateLimit-Retry-After", String.valueOf(retryAfterSecs));
        response.getHeaders().add("X-RateLimit-Limit",    String.valueOf(rule.limit()));
        response.getHeaders().add("X-RateLimit-Reset",    String.valueOf(resetEpoch));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String message = buildRateLimitMessage(rule.bucketName(), retryAfterSecs);
        String body = "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"" + message
                + "\",\"retryAfterSeconds\":" + retryAfterSecs
                + ",\"resetAt\":" + resetEpoch + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Sanitize a client-supplied binge ID before embedding it in a Redis key.
     * Only numeric digits are accepted (binge IDs are database-generated longs),
     * capped at 20 chars. Returns null for any other input so the bucket falls
     * back to the global (non-scoped) key rather than polluting the key namespace.
     */
    private String sanitizeBingeId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isEmpty() || digits.length() > 20 ? null : digits;
    }

    private String buildRateLimitMessage(String bucketName, long retryAfterSecs) {
        String waitTime = retryAfterSecs >= 60
                ? (retryAfterSecs / 60) + " minute" + (retryAfterSecs / 60 > 1 ? "s" : "")
                : retryAfterSecs + " second" + (retryAfterSecs != 1 ? "s" : "");
        return switch (bucketName) {
            case "booking-create"   -> "You've made too many booking requests. Please wait " + waitTime + " and try again.";
            case "payment-initiate" -> "Too many payment attempts for this venue. Please wait " + waitTime + " before retrying.";
            case "forgot-password"  -> "Too many password reset requests. Please wait " + waitTime + " before retrying.";
            case "verify-otp"       -> "Too many verification attempts. Please wait " + waitTime + " before retrying.";
            default                 -> "Too many requests. Please wait " + waitTime + " before trying again.";
        };
    }

    private String resolveRedisCounterKey(String bucketKey, Duration window) {
        // Quantize the current instant by the rule's window so each window gets its own counter.
        long windowSec = window.toSeconds();
        long bucketSlot = Instant.now().getEpochSecond() / windowSec;
        return "user-rate-limit:" + bucketKey + ':' + bucketSlot;
    }

    private void logRedisFallback(Throwable ex) {
        long now = System.currentTimeMillis();
        long lastLoggedAt = lastRedisFallbackLogAt.get();
        if (now - lastLoggedAt >= REDIS_FALLBACK_LOG_WINDOW_MS
            && lastRedisFallbackLogAt.compareAndSet(lastLoggedAt, now)) {
            log.warn("Redis-backed user rate limiting unavailable, falling back to local buckets: {}", ex.getMessage());
        }
    }
}
