package com.skbingegalaxy.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.notification.dto.DeliveryEventDto;
import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Webhook endpoint for receiving delivery status callbacks from
 * email providers (SendGrid, Mailgun, SES, etc.).
 *
 * <p>The endpoint is unauthenticated at the JWT layer so external providers
 * can POST to it (the gateway allowlists {@code /api/v1/notifications/webhooks/}).
 * Security is enforced by an HMAC-SHA256 signature over the raw request body:
 * the provider (or the relay that forwards provider events) signs the body with
 * the shared secret from {@code NOTIFICATION_WEBHOOK_SECRET} and sends the
 * lowercase hex digest in {@code X-Webhook-Signature}.
 *
 * <p>Fail-closed: if the secret is not configured, every request is rejected —
 * a publicly routed endpoint must never accept unsigned status mutations.
 */
@RestController
@RequestMapping("/api/v1/notifications/webhooks")
@RequiredArgsConstructor
@Slf4j
public class DeliveryWebhookController {

    private static final Set<String> VALID_EVENTS = Set.of(
            "delivered", "bounced", "opened", "clicked", "complained");

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.notification.webhook-secret:}")
    private String webhookSecret;

    /**
     * Generic delivery event webhook.
     * Verifies the HMAC signature over the raw body, then validates the event
     * type, checks idempotency, and updates delivery status.
     */
    @PostMapping("/delivery")
    public ResponseEntity<Void> handleDeliveryEvent(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (!verifySignature(rawBody, signature)) {
            return ResponseEntity.status(401).build();
        }

        // Parse AFTER signature verification so the DTO is only ever built from
        // an authenticated body (and a forged body can't drive parser errors).
        DeliveryEventDto payload;
        try {
            payload = objectMapper.readValue(rawBody, DeliveryEventDto.class);
        } catch (Exception e) {
            log.warn("Delivery webhook rejected — unparseable body: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        if (payload.getNotificationId() == null || payload.getNotificationId().isBlank()
                || payload.getEvent() == null || payload.getEvent().isBlank()) {
            log.warn("Delivery webhook rejected — missing notificationId or event");
            return ResponseEntity.badRequest().build();
        }

        String event = payload.getEvent().toLowerCase(Locale.ROOT);
        if (!VALID_EVENTS.contains(event)) {
            log.warn("Delivery webhook ignored — unknown event type: {}", event);
            return ResponseEntity.badRequest().build();
        }

        return notificationRepository.findById(payload.getNotificationId())
            .map(notification -> {
                // Idempotency: skip if notification is already in a terminal/same state
                DeliveryStatus targetStatus = mapEventToStatus(event);
                if (notification.getDeliveryStatus() == targetStatus) {
                    log.debug("Delivery webhook idempotent skip: id={} already in state {}",
                            payload.getNotificationId(), targetStatus);
                    return ResponseEntity.ok().<Void>build();
                }

                updateDeliveryStatus(notification, event);
                notificationRepository.save(notification);
                log.info("Delivery webhook processed: id={} event={} providerMsgId={}",
                        payload.getNotificationId(), event, payload.getProviderMessageId());
                return ResponseEntity.ok().<Void>build();
            })
            .orElseGet(() -> {
                log.warn("Delivery webhook for unknown notification: {}", payload.getNotificationId());
                return ResponseEntity.notFound().build();
            });
    }

    /**
     * Validates the {@code X-Webhook-Signature} header: lowercase hex
     * HMAC-SHA256 of the raw request body keyed with the shared secret.
     * Constant-time comparison prevents timing side-channels.
     */
    boolean verifySignature(String rawBody, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("NOTIFICATION_WEBHOOK_SECRET not configured — delivery webhook rejected (fail-closed)");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            log.warn("Delivery webhook rejected — missing X-Webhook-Signature header");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            if (!valid) {
                log.warn("Delivery webhook rejected — invalid signature");
            }
            return valid;
        } catch (Exception e) {
            log.error("Delivery webhook signature verification error", e);
            return false;
        }
    }

    private DeliveryStatus mapEventToStatus(String event) {
        return switch (event) {
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "bounced" -> DeliveryStatus.BOUNCED;
            case "opened" -> DeliveryStatus.OPENED;
            case "clicked" -> DeliveryStatus.CLICKED;
            case "complained" -> DeliveryStatus.COMPLAINED;
            default -> null;
        };
    }

    private void updateDeliveryStatus(Notification notification, String event) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Record the engagement timestamp as an independent fact, set-once, so a webhook that
        // arrives out of order can't overwrite an earlier, more accurate time.
        switch (event) {
            case "delivered" -> { if (notification.getDeliveredAt() == null) notification.setDeliveredAt(now); }
            case "opened"    -> { if (notification.getOpenedAt() == null) notification.setOpenedAt(now); }
            case "clicked"   -> { if (notification.getClickedAt() == null) notification.setClickedAt(now); }
            case "bounced", "complained" -> { if (notification.getBouncedAt() == null) notification.setBouncedAt(now); }
        }

        // The headline status only moves FORWARD along the positive ladder
        // (SENT → DELIVERED → OPENED → CLICKED); a terminal-negative (BOUNCED/COMPLAINED) is
        // sticky. Providers don't guarantee ordering, so a late "delivered" must not downgrade
        // a "clicked", and a late positive event must not clear a "bounced"/"complained".
        DeliveryStatus target = mapEventToStatus(event);
        if (shouldAdvanceStatus(notification.getDeliveryStatus(), target)) {
            notification.setDeliveryStatus(target);
        }
    }

    /** Rank along the positive engagement ladder; terminal-negative states are handled separately. */
    private static int engagementRank(DeliveryStatus s) {
        if (s == null) return 0;
        return switch (s) {
            case PENDING -> 0;
            case SENT -> 1;
            case DELIVERED -> 2;
            case OPENED -> 3;
            case CLICKED -> 4;
            case BOUNCED, COMPLAINED -> -1;
        };
    }

    /**
     * Whether a webhook event should overwrite the current headline status. Forward-only on the
     * positive ladder; negative signals (bounce/complaint) always apply; a positive event never
     * clears an existing terminal-negative state.
     */
    static boolean shouldAdvanceStatus(DeliveryStatus current, DeliveryStatus target) {
        if (target == null) return false;
        if (current == null) return true;
        boolean currentNegative = current == DeliveryStatus.BOUNCED || current == DeliveryStatus.COMPLAINED;
        boolean targetNegative = target == DeliveryStatus.BOUNCED || target == DeliveryStatus.COMPLAINED;
        if (currentNegative && !targetNegative) return false;   // don't clear a bounce/complaint
        if (targetNegative) return true;                        // always record a negative signal
        return engagementRank(target) > engagementRank(current); // positive ladder: forward only
    }
}
