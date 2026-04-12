package com.skbingegalaxy.gateway.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final int MAX_BUCKETS = 10_000;
    private static final int STANDARD_RATE_LIMIT = 100;
    private static final int AUTH_RATE_LIMIT = 30;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final Duration REDIS_COUNTER_TTL = RATE_LIMIT_WINDOW.plusSeconds(5);
    private static final long REDIS_FALLBACK_LOG_WINDOW_MS = 60_000;
    private static final String RETRY_AFTER_SECONDS = "60";

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
        this(null);
    }

    @Autowired
    public RateLimitFilter(@Autowired(required = false) ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = resolveClientIp(exchange);
        String path = exchange.getRequest().getURI().getPath();
        boolean isAuthPath = path.startsWith("/api/v1/auth/");
        String bucketKey = isAuthPath ? clientIp + ":auth" : clientIp;
        int limit = isAuthPath ? AUTH_RATE_LIMIT : STANDARD_RATE_LIMIT;

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
        Bandwidth bandwidth = Bandwidth.classic(limit, Refill.greedy(limit, RATE_LIMIT_WINDOW));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private Mono<Void> rejectRequest(ServerWebExchange exchange, String path, String bucketKey) {
        log.warn("Rate limit exceeded for {} on path {}", bucketKey, path);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", RETRY_AFTER_SECONDS);
        exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After", RETRY_AFTER_SECONDS);
        return exchange.getResponse().setComplete();
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
