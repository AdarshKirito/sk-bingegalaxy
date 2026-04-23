package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.UserSession;
import com.skbingegalaxy.auth.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists and rotates a {@link UserSession} row for every refresh-token grant so:
 *   <ul>
 *     <li>Users and super admins can see where the account is logged in.</li>
 *     <li>Super admins can force-logout a specific session or every session of a user.</li>
 *     <li>Refresh rotation updates the JTI rather than creating a new session row — each
 *         "device" keeps a stable session id for the duration of the login.</li>
 *   </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSessionService {

    private final UserSessionRepository repository;
    private final TokenRevocationService tokenRevocationService;

    /**
     * Create a brand-new session for a fresh login (or rotate the old one if the same
     * device already has an active session — matched heuristically by UA + IP).
     */
    @Transactional
    public UserSession recordLogin(Long userId, String refreshJti, LocalDateTime expiresAt) {
        HttpServletRequest req = currentRequest();
        String ua = trim(headerOf(req, "User-Agent"), 512);
        String ip = extractIp(req);

        UserSession session = UserSession.builder()
            .userId(userId)
            .refreshJti(refreshJti)
            .ipAddress(ip)
            .userAgent(ua)
            .deviceLabel(shortDeviceLabel(ua))
            .expiresAt(expiresAt)
            .build();
        return repository.save(session);
    }

    /**
     * Rotate a session row when the refresh token is exchanged for a new one. Returns
     * the updated row or empty if no matching (non-revoked) session exists — in which
     * case the caller should reject the refresh attempt.
     */
    @Transactional
    public Optional<UserSession> rotate(String oldJti, String newJti, LocalDateTime newExpiresAt) {
        return repository.findByRefreshJti(oldJti).map(s -> {
            if (s.getRevokedAt() != null) return null;
            s.touch(newJti, newExpiresAt);
            return repository.save(s);
        }).map(Optional::of).orElseGet(() -> {
            HttpServletRequest req = currentRequest();
            log.debug("Rotate called with unknown jti={} ip={}", oldJti, extractIp(req));
            return Optional.empty();
        });
    }

    /**
     * Revoke a single session (by id) on behalf of another user (usually a super admin).
     * The backing refresh-token JTI is also placed in {@code revoked_token} so it cannot
     * be replayed against /auth/refresh.
     */
    @Transactional
    public boolean revoke(Long sessionId, Long byUserId, String reason) {
        return repository.findById(sessionId).map(s -> {
            if (s.getRevokedAt() != null) return false;
            s.revoke(byUserId, reason);
            repository.save(s);
            // Best-effort: add the JTI to the revocation list so a leaked token can't
            // be replayed against /auth/refresh.
            try {
                tokenRevocationService.revokeByJti(s.getRefreshJti(), s.getUserId(), "refresh", s.getExpiresAt());
            } catch (Exception ex) {
                log.warn("Failed to add session jti to revocation list: {}", ex.getMessage());
            }
            return true;
        }).orElse(false);
    }

    /**
     * Revoke every active session for a user (e.g. after password reset or role change).
     */
    @Transactional
    public int revokeAllForUser(Long userId, Long byUserId, String reason) {
        List<UserSession> active = repository.findActiveByUserId(userId);
        for (UserSession s : active) {
            s.revoke(byUserId, reason);
            try {
                tokenRevocationService.revokeByJti(s.getRefreshJti(), s.getUserId(), "refresh", s.getExpiresAt());
            } catch (Exception ex) {
                log.debug("Skip revocation-list add for session {}: {}", s.getId(), ex.getMessage());
            }
        }
        if (!active.isEmpty()) {
            repository.saveAll(active);
        }
        return active.size();
    }

    @Transactional(readOnly = true)
    public List<UserSession> listActiveForUser(Long userId) {
        return repository.findActiveByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Page<UserSession> listAllActive(Pageable pageable) {
        return repository.findAllActive(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<UserSession> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<UserSession> findByJti(String jti) {
        return repository.findByRefreshJti(jti);
    }

    /** Purges expired session rows (kept 24h past expiry so audit UIs can still show them). */
    @Scheduled(fixedDelayString = "${app.security.sessions.cleanup-interval-ms:3600000}",
               initialDelayString = "${app.security.sessions.cleanup-initial-delay-ms:120000}")
    @SchedulerLock(name = "userSessionCleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
        if (deleted > 0) log.info("Purged {} expired user_session rows", deleted);
    }

    // ── helpers ──────────────────────────────────────────────
    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) { }
        return null;
    }

    private String extractIp(HttpServletRequest req) {
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return trim((comma > 0 ? xff.substring(0, comma) : xff).trim(), 64);
        }
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return trim(realIp.trim(), 64);
        return trim(req.getRemoteAddr(), 64);
    }

    private String headerOf(HttpServletRequest req, String name) {
        if (req == null) return null;
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    private String trim(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

    /** Condense a UA string into a short human-friendly label. */
    private String shortDeviceLabel(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String lower = ua.toLowerCase();
        String os = "Unknown OS";
        if (lower.contains("windows")) os = "Windows";
        else if (lower.contains("iphone") || lower.contains("ios")) os = "iOS";
        else if (lower.contains("android")) os = "Android";
        else if (lower.contains("mac os") || lower.contains("macintosh")) os = "macOS";
        else if (lower.contains("linux")) os = "Linux";
        String browser = "browser";
        if (lower.contains("edg/")) browser = "Edge";
        else if (lower.contains("chrome/") && !lower.contains("edg/")) browser = "Chrome";
        else if (lower.contains("firefox/")) browser = "Firefox";
        else if (lower.contains("safari/") && !lower.contains("chrome/")) browser = "Safari";
        return browser + " on " + os;
    }
}
