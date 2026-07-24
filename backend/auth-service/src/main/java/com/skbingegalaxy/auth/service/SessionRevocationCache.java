package com.skbingegalaxy.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed denylist of force-revoked session ids ({@code sid} claim).
 *
 * <p>WHY: access tokens are stateless and live 15 minutes. Revoking a session
 * used to only block the refresh token, so the revoked device stayed signed in
 * until the access token aged out — "Revoke" looked like it did nothing. Now
 * every access token carries the {@code sid} of its session row, the gateway
 * checks this denylist on every request, and a revoked session dies instantly.
 *
 * <p>Key: {@code auth:revoked-sid:{sessionId}} with TTL = access-token expiry
 * + 60s skew — after that the tokens have expired on their own, so the entry
 * is garbage and Redis reclaims it.
 *
 * <p>Fail-safe: Redis being down must never break revocation itself (the DB
 * row + refresh-JTI revocation still stand); we log loudly and move on — the
 * behavior degrades to the old "dies at natural expiry" semantics.
 */
@Service
@Slf4j
public class SessionRevocationCache {

    public static final String KEY_PREFIX = "auth:revoked-sid:";

    private final StringRedisTemplate redis;
    private final long accessTokenTtlMs;

    public SessionRevocationCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${app.jwt.expiration-ms:900000}") long accessTokenTtlMs) {
        // ObjectProvider (not a required param): the cache degrades gracefully
        // when Redis auto-config is absent instead of failing startup.
        this.redis = redisProvider != null ? redisProvider.getIfAvailable() : null;
        this.accessTokenTtlMs = accessTokenTtlMs;
        if (this.redis == null) {
            log.warn("SessionRevocationCache: no Redis connection configured — "
                + "force-revoked sessions will keep working until access-token expiry");
        }
    }

    /** Denylist one session id. Best-effort; never throws. */
    public void denylist(Long sessionId) {
        if (sessionId == null || redis == null) return;
        try {
            redis.opsForValue().set(
                KEY_PREFIX + sessionId, "1",
                Duration.ofMillis(accessTokenTtlMs + 60_000L));
        } catch (Exception ex) {
            log.error("Failed to denylist revoked session {} in Redis — its access token "
                + "remains valid until natural expiry: {}", sessionId, ex.getMessage());
        }
    }
}
