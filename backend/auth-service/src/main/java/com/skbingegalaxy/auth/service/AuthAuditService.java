package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.AuthAuditLog;
import com.skbingegalaxy.auth.repository.AuthAuditLogRepository;
import com.skbingegalaxy.common.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records every auth-sensitive event to the {@code auth_audit_log} table. Never throws
 * to the caller — audit failures must not block a login or admin action. Runs in its own
 * {@code REQUIRES_NEW} transaction so a failing business transaction doesn't lose the
 * corresponding audit row (and vice-versa).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthAuditService {

    private final AuthAuditLogRepository repository;

    public enum EventType {
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        LOGIN_LOCKED,
        LOGIN_MFA_CHALLENGED,
        LOGIN_MFA_FAILED,
        LOGOUT,
        TOKEN_REFRESHED,
        REGISTER,
        PASSWORD_CHANGED,
        PASSWORD_RESET_REQUESTED,
        PASSWORD_RESET_COMPLETED,
        EMAIL_VERIFICATION_SENT,
        EMAIL_VERIFIED,
        MFA_ENROLLED,
        MFA_DISABLED,
        MFA_RECOVERY_USED,
        ADMIN_CREATED,
        ADMIN_UPDATED,
        USER_DELETED,
        USER_BULK_DELETED,
        USER_BANNED,
        USER_UNBANNED,
        ROLE_PROMOTED,
        ROLE_DEMOTED,
        SESSION_REVOKED,
        SESSION_REVOKED_ALL
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EventType eventType,
                       Long actorId, UserRole actorRole,
                       Long targetId, String targetEmail,
                       boolean success, String failureReason, String details) {
        try {
            HttpServletRequest request = currentRequest();
            AuthAuditLog entry = AuthAuditLog.builder()
                .eventType(eventType.name())
                .actorId(actorId)
                .actorRole(actorRole == null ? null : actorRole.name())
                .targetId(targetId)
                .targetEmail(targetEmail)
                .ipAddress(extractIp(request))
                .userAgent(trim(headerOf(request, "User-Agent"), 512))
                .requestId(headerOf(request, "X-Request-Id"))
                .success(success)
                .failureReason(trim(failureReason, 255))
                .details(details)
                .build();
            repository.save(entry);
        } catch (Exception ex) {
            // Audit must never break the request flow.
            log.warn("Failed to record auth audit event {}: {}", eventType, ex.getMessage());
        }
    }

    public void success(EventType eventType, Long actorId, UserRole actorRole, Long targetId, String targetEmail, String details) {
        record(eventType, actorId, actorRole, targetId, targetEmail, true, null, details);
    }

    public void failure(EventType eventType, Long actorId, UserRole actorRole, Long targetId, String targetEmail, String reason) {
        record(eventType, actorId, actorRole, targetId, targetEmail, false, reason, null);
    }

    @Transactional(readOnly = true)
    public Page<AuthAuditLog> search(String eventType, Long actorId, Long targetId, Pageable pageable) {
        return repository.search(
            (eventType == null || eventType.isBlank()) ? null : eventType,
            actorId, targetId, pageable);
    }

    // ── helpers ──────────────────────────────────────────────
    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (Exception ignored) { /* best-effort */ }
        return null;
    }

    private String extractIp(HttpServletRequest req) {
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            return trim(first, 64);
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
}
