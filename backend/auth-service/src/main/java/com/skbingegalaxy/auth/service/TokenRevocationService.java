package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.RevokedToken;
import com.skbingegalaxy.auth.repository.RevokedTokenRepository;
import com.skbingegalaxy.auth.security.JwtProvider;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Tracks JWTs that have been revoked before their natural expiry.
 * <p>
 * On logout we persist the JTI of the presented access and refresh tokens so that
 * subsequent calls to {@code /auth/refresh} with a revoked refresh token are rejected
 * even though the signature is still valid. Rows are purged automatically once they
 * pass their {@code expiresAt} to keep the table bounded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRevocationService {

    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtProvider jwtProvider;

    /**
     * Revoke a single token. Silently tolerates malformed / already-expired tokens so
     * logout is always idempotent from the client's perspective.
     */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            String jti = jwtProvider.getJtiFromToken(token);
            if (jti == null || jti.isBlank()) {
                // Legacy token minted before jti rollout — nothing to pin.
                return;
            }
            if (revokedTokenRepository.existsByJti(jti)) {
                return;
            }
            LocalDateTime expiresAt = jwtProvider.getExpiryFromToken(token);
            if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
                return;
            }
            Long userId = null;
            try {
                userId = jwtProvider.getUserIdFromToken(token);
            } catch (Exception ignored) {
                // Not critical; we can revoke without the user id.
            }
            String type = safeTokenType(token);
            revokedTokenRepository.save(RevokedToken.builder()
                .jti(jti)
                .userId(userId)
                .tokenType(type)
                .expiresAt(expiresAt)
                .build());
            log.debug("Revoked {} token jti={} user={}", type, jti, userId);
        } catch (JwtException | IllegalArgumentException ex) {
            // Expired or malformed token — no need to track.
            log.debug("revoke: ignoring unparseable token ({})", ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        return jti != null && !jti.isBlank() && revokedTokenRepository.existsByJti(jti);
    }

    private String safeTokenType(String token) {
        try {
            String t = jwtProvider.getTokenType(token);
            return (t == null || t.isBlank()) ? "unknown" : t;
        } catch (Exception ex) {
            return "unknown";
        }
    }

    /**
     * Purges revoked-token rows whose original expiry has already passed. After natural
     * expiry the token is rejected by signature validation anyway, so we no longer
     * need to track it.
     */
    @Scheduled(fixedDelayString = "${app.security.revocation.cleanup-interval-ms:3600000}",
               initialDelayString = "${app.security.revocation.cleanup-initial-delay-ms:60000}")
    @SchedulerLock(name = "revokedTokenCleanup",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpired() {
        int deleted = revokedTokenRepository.deleteAllByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired revoked-token rows", deleted);
        }
    }
}
