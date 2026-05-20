package com.skbingegalaxy.booking.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pulls forensic metadata (client IP, User-Agent) out of the current
 * Spring MVC request without forcing every controller to thread the
 * {@link HttpServletRequest} through service calls. Safe to call from
 * service layers — returns {@code null} when there is no active request
 * (e.g. scheduled jobs, Kafka consumers).
 *
 * <p>IP resolution honours {@code X-Forwarded-For} (left-most entry —
 * the original client) when present, then {@code X-Real-IP}, then the
 * raw {@code remoteAddr}. Truncated to 45 chars (IPv6 max).
 */
public final class RequestContext {

    private RequestContext() {}

    public static String currentIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isBlank()) return cap(first, 45);
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return cap(real.trim(), 45);
        String remote = req.getRemoteAddr();
        return remote == null ? null : cap(remote, 45);
    }

    public static String currentUserAgent() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String ua = req.getHeader("User-Agent");
        return ua == null || ua.isBlank() ? null : cap(ua, 500);
    }

    /**
     * Returns the canonical role string from the upstream gateway header
     * {@code X-User-Role} (e.g. {@code CUSTOMER}, {@code ADMIN},
     * {@code SUPER_ADMIN}). {@code null} when called outside a request
     * scope (Kafka consumers, scheduled jobs) — callers should treat that
     * as the {@code SYSTEM} actor.
     */
    public static String currentRole() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String role = req.getHeader("X-User-Role");
        return role == null || role.isBlank() ? null : role.trim().toUpperCase();
    }

    /**
     * Numeric user id from {@code X-User-Id} (gateway-injected). Returns
     * {@code null} for unauthenticated / system contexts or when the
     * header is malformed.
     */
    public static Long currentUserId() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String raw = req.getHeader("X-User-Id");
        if (raw == null || raw.isBlank()) return null;
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    /**
     * Display name from {@code X-User-Name} for audit-log readability.
     * Snapshotted into {@code BookingEventLog.triggeredByName} so historical
     * rows survive name changes / account deletion.
     */
    public static String currentUserName() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String name = req.getHeader("X-User-Name");
        return name == null || name.isBlank() ? null : cap(name.trim(), 150);
    }

    /**
     * Distributed-trace correlation id for the current request.
     * <ol>
     *   <li>If the upstream sent {@code X-Correlation-Id}, returns that
     *       (so id propagates end-to-end across the gateway).</li>
     *   <li>Otherwise falls back to MDC key {@code correlationId}, which
     *       Sleuth/Brave or our own filter typically sets.</li>
     *   <li>Returns {@code null} outside a request scope (e.g. Kafka
     *       consumer threads, scheduled jobs) — callers should fall back
     *       to {@link java.util.UUID#randomUUID()}.</li>
     * </ol>
     */
    public static String currentCorrelationId() {
        HttpServletRequest req = currentRequest();
        if (req != null) {
            String header = req.getHeader("X-Correlation-Id");
            if (header != null && !header.isBlank()) return cap(header.trim(), 64);
        }
        String mdc = org.slf4j.MDC.get("correlationId");
        return mdc == null || mdc.isBlank() ? null : cap(mdc, 64);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
