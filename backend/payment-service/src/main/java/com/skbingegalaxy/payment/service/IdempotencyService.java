package com.skbingegalaxy.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.payment.entity.IdempotencyKey;
import com.skbingegalaxy.payment.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stripe-style {@code Idempotency-Key} handling for money-moving POST endpoints.
 *
 * <p>Semantics, matching Stripe's contract:
 * <ul>
 *   <li><b>Hit (same key + same payload):</b> return the cached response —
 *       duplicate network retries / double-clicks become no-ops.</li>
 *   <li><b>Mismatch (same key, different payload):</b> {@code 409 Conflict}.
 *       Surfaces the client bug of reusing an idempotency key for a new
 *       operation instead of silently running the new one.</li>
 *   <li><b>Miss:</b> run the operation; persist response + hash on success.</li>
 * </ul>
 *
 * <p>Scoping is {@code (key, method, path, userId)} so the same key value
 * reused across endpoints or users never collides. TTL is 24h (Stripe default);
 * the {@link #purgeExpired()} scheduler prunes stale rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;

    @Value("${app.idempotency.ttl-hours:24}")
    private int ttlHours;

    /**
     * Execute {@code work} at most once per (key, method, path, userId) tuple,
     * returning the cached response on replay. When {@code key} is blank the
     * caller is not requesting idempotency — {@code work} is invoked directly.
     */
    @Transactional
    public <T> T execute(String key,
                         String httpMethod,
                         String requestPath,
                         Long userId,
                         Object requestPayload,
                         Class<T> responseType,
                         Supplier<T> work) {
        if (key == null || key.isBlank()) {
            return work.get();
        }
        if (key.length() > 128) {
            throw new BusinessException("Idempotency-Key must be at most 128 characters", HttpStatus.BAD_REQUEST);
        }

        String payloadHash = sha256(safeSerialize(requestPayload));

        Optional<IdempotencyKey> existing = repository
            .findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(key, httpMethod, requestPath, userId);

        if (existing.isPresent()) {
            IdempotencyKey stored = existing.get();
            // Expired rows are treated as "not present" and replaced by the current run.
            if (stored.getExpiresAt() != null && stored.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.info("Idempotency key {} expired — re-running work", key);
                repository.delete(stored);
                // fall through to miss path
            } else if (!stored.getRequestHash().equals(payloadHash)) {
                metrics.idempotencyMismatch();
                log.warn("Idempotency key {} reused with a different payload (method={}, path={}, user={})",
                    key, httpMethod, requestPath, userId);
                throw new BusinessException(
                    "Idempotency-Key was previously used with a different request payload. "
                        + "Pick a new key for a new operation.",
                    HttpStatus.CONFLICT);
            } else {
                metrics.idempotencyHit();
                log.debug("Idempotency hit for key {} (method={}, path={})", key, httpMethod, requestPath);
                return deserialize(stored.getResponseBody(), responseType);
            }
        }

        T response = work.get();

        try {
            IdempotencyKey row = IdempotencyKey.builder()
                .idempotencyKey(key)
                .httpMethod(httpMethod)
                .requestPath(requestPath)
                .userId(userId)
                .requestHash(payloadHash)
                .responseStatus(200)
                .responseBody(objectMapper.writeValueAsString(response))
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .build();
            repository.save(row);
            metrics.idempotencyStored();
        } catch (JsonProcessingException e) {
            // Serialization failures are developer errors, not client errors.
            // We've already performed the real work — don't roll it back because
            // we couldn't cache the response. Next retry will just re-run it.
            log.error("Failed to cache idempotency response for key {}: {}", key, e.getMessage());
        }
        return response;
    }

    /** Hourly pruning of expired idempotency rows. Cluster-safe via ShedLock. */
    @Scheduled(
        fixedDelayString = "${app.idempotency.cleanup-interval-ms:3600000}",
        initialDelayString = "${app.idempotency.cleanup-initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "idempotencyKeyCleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpired() {
        int removed = repository.deleteAllByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) log.info("Purged {} expired idempotency keys", removed);
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private String safeSerialize(Object payload) {
        if (payload == null) return "";
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // If we can't hash the payload, fail closed rather than silently accept
            // whatever the client sent — otherwise an attacker could replay any
            // subsequent request through the same key.
            throw new BusinessException(
                "Unable to serialize request for idempotency hashing",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached idempotency response: {}", e.getMessage());
            throw new BusinessException(
                "Idempotency cache corrupted — please retry with a new key",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
