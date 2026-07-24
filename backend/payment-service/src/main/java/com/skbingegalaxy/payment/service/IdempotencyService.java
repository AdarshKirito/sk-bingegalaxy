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
import java.time.ZoneOffset;
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

    /** Marker status for a claim whose work has not completed yet. */
    private static final int STATUS_IN_PROGRESS = 0;

    /** How long an unfinished claim blocks concurrent duplicates before it is presumed crashed. */
    private static final int IN_PROGRESS_TTL_MINUTES = 5;

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;

    /** Self-proxy so the REQUIRES_NEW claim/release methods go through AOP. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private IdempotencyService self;

    @Value("${app.idempotency.ttl-hours:24}")
    private int ttlHours;

    /**
     * Execute {@code work} at most once per (key, method, path, userId) tuple,
     * returning the cached response on replay. When {@code key} is blank the
     * caller is not requesting idempotency — {@code work} is invoked directly.
     *
     * <p>Concurrency (claim-first): an in-progress claim row is committed in
     * its own transaction BEFORE the work runs, so two SIMULTANEOUS requests
     * with the same key collide on the composite primary key — exactly one
     * moves money; the other gets 409 (or the cached response once the winner
     * finished). The previous check-then-act version only defended sequential
     * retries. Crashed claims expire after {@value #IN_PROGRESS_TTL_MINUTES}
     * minutes and become re-claimable.</p>
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Optional<IdempotencyKey> existing = repository
            .findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(key, httpMethod, requestPath, userId);

        if (existing.isPresent()) {
            IdempotencyKey stored = existing.get();
            // Expired rows are treated as "not present" and replaced by the current run.
            if (stored.getExpiresAt() != null && stored.getExpiresAt().isBefore(now)) {
                log.info("Idempotency key {} expired — re-running work", key);
                self.deleteRow(stored);
                // fall through to claim path
            } else if (!stored.getRequestHash().equals(payloadHash)) {
                metrics.idempotencyMismatch();
                log.warn("Idempotency key {} reused with a different payload (method={}, path={}, user={})",
                    key, httpMethod, requestPath, userId);
                throw new BusinessException(
                    "Idempotency-Key was previously used with a different request payload. "
                        + "Pick a new key for a new operation.",
                    HttpStatus.CONFLICT);
            } else if (stored.getResponseBody() == null) {
                // A null body is the in-progress marker: claims are inserted
                // body-less and only completed work fills the response in.
                throw new BusinessException(
                    "A request with this Idempotency-Key is still being processed. Please retry shortly.",
                    HttpStatus.CONFLICT);
            } else {
                metrics.idempotencyHit();
                log.debug("Idempotency hit for key {} (method={}, path={})", key, httpMethod, requestPath);
                return deserialize(stored.getResponseBody(), responseType);
            }
        }

        // ── Claim ────────────────────────────────────────────────────────────
        try {
            self.insertClaim(key, httpMethod, requestPath, userId, payloadHash);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            Optional<IdempotencyKey> winner = repository
                .findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(key, httpMethod, requestPath, userId);
            if (winner.isPresent() && winner.get().getResponseBody() != null) {
                if (!winner.get().getRequestHash().equals(payloadHash)) {
                    metrics.idempotencyMismatch();
                    throw new BusinessException(
                        "Idempotency-Key was previously used with a different request payload. "
                            + "Pick a new key for a new operation.",
                        HttpStatus.CONFLICT);
                }
                metrics.idempotencyHit();
                return deserialize(winner.get().getResponseBody(), responseType);
            }
            throw new BusinessException(
                "A request with this Idempotency-Key is already being processed. Please retry shortly.",
                HttpStatus.CONFLICT);
        }

        T response;
        try {
            response = work.get();
        } catch (RuntimeException | Error e) {
            try {
                self.releaseClaim(key, httpMethod, requestPath, userId);
            } catch (Exception releaseFailure) {
                log.warn("Could not release idempotency claim after failure: {}", releaseFailure.getMessage());
            }
            throw e;
        }

        try {
            IdempotencyKey row = repository
                .findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(key, httpMethod, requestPath, userId)
                .orElse(null);
            if (row != null) {
                row.setResponseStatus(200);
                row.setResponseBody(objectMapper.writeValueAsString(response));
                row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(ttlHours));
                repository.save(row);
                metrics.idempotencyStored();
            }
        } catch (JsonProcessingException e) {
            // Serialization failures are developer errors, not client errors.
            // We've already performed the real work — don't roll it back because
            // we couldn't cache the response. Next retry will just re-run it.
            log.error("Failed to cache idempotency response for key {}: {}", key, e.getMessage());
        }
        return response;
    }

    /** Commits the in-progress claim immediately so concurrent duplicates collide on the PK. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void insertClaim(String key, String httpMethod, String requestPath, Long userId, String payloadHash) {
        repository.saveAndFlush(IdempotencyKey.builder()
            .idempotencyKey(key)
            .httpMethod(httpMethod)
            .requestPath(requestPath)
            .userId(userId)
            .requestHash(payloadHash)
            .responseStatus(STATUS_IN_PROGRESS)
            .responseBody(null)
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(IN_PROGRESS_TTL_MINUTES))
            .build());
    }

    /** Frees a claim after the work failed — own transaction, the caller's is rolling back. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void releaseClaim(String key, String httpMethod, String requestPath, Long userId) {
        repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(key, httpMethod, requestPath, userId)
            .filter(row -> row.getResponseBody() == null)
            .ifPresent(repository::delete);
    }

    /** Deletes an expired row in its own transaction (see claim path). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void deleteRow(IdempotencyKey row) {
        repository.delete(row);
    }

    /** Hourly pruning of expired idempotency rows. Cluster-safe via ShedLock. */
    @Scheduled(
        fixedDelayString = "${app.idempotency.cleanup-interval-ms:3600000}",
        initialDelayString = "${app.idempotency.cleanup-initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "idempotencyKeyCleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpired() {
        int removed = repository.deleteAllByExpiresAtBefore(LocalDateTime.now(ZoneOffset.UTC));
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
