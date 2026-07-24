package com.skbingegalaxy.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.notification.dto.PushSubscriptionRequest;
import com.skbingegalaxy.notification.model.PushSubscription;
import com.skbingegalaxy.notification.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser Web Push delivery + subscription lifecycle.
 *
 * <p>Sits alongside (not inside) the channel router: a subscribed user receives an
 * encrypted push <em>in addition</em> to their primary email/in-app notification, which
 * is how mainstream apps behave. Fan-out is keyed on the recipient's email so every
 * device the user registered is reached. Dead endpoints (HTTP 404/410) are pruned on the
 * spot; the whole path is best-effort and never throws into the notification pipeline.
 *
 * <p>When no VAPID key pair is configured the {@link PushService} bean is {@code null}
 * and every send is a silent no-op — email/in-app delivery is unaffected.
 */
@Service
@Slf4j
public class WebPushService {

    /** After this many consecutive failures we drop a subscription even without a 404/410. */
    private static final int MAX_FAILURES = 8;

    private final PushService pushService; // null when VAPID keys absent
    private final PushSubscriptionRepository repo;
    private final ObjectMapper objectMapper;

    @Value("${app.webpush.public-key:}")
    private String publicKey;

    public WebPushService(@Autowired(required = false) PushService pushService,
                          PushSubscriptionRepository repo,
                          ObjectMapper objectMapper) {
        this.pushService = pushService;
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** True when browser push is actually deliverable (keys configured). */
    public boolean isEnabled() {
        return pushService != null;
    }

    /** VAPID public key the browser subscribes with (safe to expose). */
    public String getPublicKey() {
        return publicKey;
    }

    // ── Subscription lifecycle ───────────────────────────────────────────────

    /**
     * Upsert a subscription keyed on its endpoint. Re-subscribing the same browser (or a
     * different user signing in on it) updates the owner + keys rather than duplicating.
     */
    public void subscribe(String email, Long userId, PushSubscriptionRequest req) {
        if (req == null || req.getEndpoint() == null || req.getEndpoint().isBlank()
                || req.getKeys() == null
                || req.getKeys().getP256dh() == null || req.getKeys().getAuth() == null) {
            throw new IllegalArgumentException("Invalid push subscription payload");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PushSubscription sub = repo.findByEndpoint(req.getEndpoint())
                .orElseGet(() -> PushSubscription.builder()
                        .endpoint(req.getEndpoint())
                        .createdAt(now)
                        .build());
        sub.setRecipientEmail(email);
        sub.setUserId(userId);
        sub.setP256dh(req.getKeys().getP256dh());
        sub.setAuth(req.getKeys().getAuth());
        sub.setUserAgent(req.getUserAgent());
        sub.setFailureCount(0);
        sub.setLastUsedAt(now);
        repo.save(sub);
        log.info("Push subscription saved for {} (userId={})", email, userId);
    }

    /** Remove a subscription by endpoint (browser un-subscribed / permission revoked). */
    public void unsubscribe(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        repo.deleteByEndpoint(endpoint);
        log.info("Push subscription removed: endpoint={}…", safePrefix(endpoint));
    }

    // ── Fan-out ──────────────────────────────────────────────────────────────

    /**
     * Deliver a push to every subscription the given user holds. Best-effort:
     * returns the number of successful sends and never propagates an exception.
     *
     * @param email recipient (fan-out key)
     * @param title notification title
     * @param body  notification body
     * @param url   click-through path (relative, e.g. "/messages") — may be null
     * @param tag   collapse key so repeats replace rather than stack — may be null
     * @param type  business type (NEW_MESSAGE, BOOKING_CREATED, …) for client routing
     */
    public int fanout(String email, String title, String body, String url, String tag, String type) {
        if (pushService == null || email == null || email.isBlank()) return 0;
        List<PushSubscription> subs = repo.findByRecipientEmail(email);
        if (subs.isEmpty()) return 0;

        String payload = buildPayload(title, body, url, tag, type);
        int sent = 0;
        for (PushSubscription sub : subs) {
            if (deliver(sub, payload)) sent++;
        }
        return sent;
    }

    private String buildPayload(String title, String body, String url, String tag, String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title != null ? title : "SK Binge Galaxy");
        map.put("body", body != null ? body : "");
        if (url != null && !url.isBlank()) map.put("url", url);
        if (tag != null && !tag.isBlank()) map.put("tag", tag);
        if (type != null && !type.isBlank()) map.put("type", type);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            // Fall back to a minimal, definitely-serialisable payload.
            return "{\"title\":\"SK Binge Galaxy\",\"body\":\"You have a new notification\"}";
        }
    }

    /** @return true on a 2xx delivery. Prunes the row on a permanent (404/410) failure. */
    private boolean deliver(PushSubscription sub, String payload) {
        try {
            Subscription s = new Subscription(sub.getEndpoint(),
                    new Subscription.Keys(sub.getP256dh(), sub.getAuth()));
            Notification notification = new Notification(s, payload);
            Object resp = pushService.send(notification);
            int status = extractStatus(resp);
            closeQuietly(resp);

            if (status >= 200 && status < 300) {
                sub.setFailureCount(0);
                sub.setLastUsedAt(LocalDateTime.now(ZoneOffset.UTC));
                repo.save(sub);
                return true;
            }
            if (status == 404 || status == 410) {
                // Gone — the browser un-subscribed or the push service expired it.
                repo.deleteByEndpoint(sub.getEndpoint());
                log.info("Pruned expired push subscription (HTTP {}) for {}", status, sub.getRecipientEmail());
                return false;
            }
            recordFailure(sub, "HTTP " + status);
            return false;
        } catch (Exception e) {
            recordFailure(sub, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private void recordFailure(PushSubscription sub, String reason) {
        int fc = sub.getFailureCount() + 1;
        sub.setFailureCount(fc);
        if (fc >= MAX_FAILURES) {
            repo.deleteByEndpoint(sub.getEndpoint());
            log.warn("Dropped push subscription for {} after {} consecutive failures (last: {})",
                    sub.getRecipientEmail(), fc, reason);
        } else {
            repo.save(sub);
            log.debug("Push send failed for {} (attempt {}): {}", sub.getRecipientEmail(), fc, reason);
        }
    }

    /**
     * Read the HTTP status from the send response without compile-time coupling to a
     * specific Apache HttpClient major version: hc5 exposes {@code getCode()}, hc4
     * exposes {@code getStatusLine().getStatusCode()}. Returns -1 if neither is present.
     */
    private int extractStatus(Object resp) {
        if (resp == null) return -1;
        try {
            Object v = resp.getClass().getMethod("getCode").invoke(resp);
            if (v instanceof Integer i) return i;
        } catch (ReflectiveOperationException ignore) { /* try hc4 shape */ }
        try {
            Object sl = resp.getClass().getMethod("getStatusLine").invoke(resp);
            Object code = sl.getClass().getMethod("getStatusCode").invoke(sl);
            if (code instanceof Integer i) return i;
        } catch (ReflectiveOperationException ignore) { /* unknown shape */ }
        return -1;
    }

    private void closeQuietly(Object resp) {
        if (resp instanceof java.io.Closeable c) {
            try { c.close(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    private static String safePrefix(String s) {
        return s == null ? "" : s.substring(0, Math.min(24, s.length()));
    }
}
