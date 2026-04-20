package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.notification.dto.DeliveryEventDto;
import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Webhook endpoint for receiving delivery status callbacks from
 * email providers (SendGrid, Mailgun, SES, etc.).
 *
 * <p>The endpoint is unauthenticated so external providers can POST to it.
 * If a webhook signing secret is configured, the {@code X-Webhook-Signature}
 * header is validated before processing.
 */
@RestController
@RequestMapping("/api/v1/notifications/webhooks")
@RequiredArgsConstructor
@Slf4j
public class DeliveryWebhookController {

    private static final Set<String> VALID_EVENTS = Set.of(
            "delivered", "bounced", "opened", "clicked", "complained");

    private final NotificationRepository notificationRepository;

    @Value("${app.notification.webhook-secret:}")
    private String webhookSecret;

    /**
     * Generic delivery event webhook.
     * Validates the event type, checks idempotency, and updates delivery status.
     */
    @PostMapping("/delivery")
    public ResponseEntity<Void> handleDeliveryEvent(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @Valid @RequestBody DeliveryEventDto payload) {

        // ── Signature verification (if configured) ──
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (signature == null || signature.isBlank()) {
                log.warn("Delivery webhook rejected — missing X-Webhook-Signature header");
                return ResponseEntity.status(401).build();
            }
            if (!webhookSecret.equals(signature)) {
                log.warn("Delivery webhook rejected — invalid signature");
                return ResponseEntity.status(401).build();
            }
        }

        String event = payload.getEvent().toLowerCase();
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
        LocalDateTime now = LocalDateTime.now();
        switch (event) {
            case "delivered" -> {
                notification.setDeliveryStatus(DeliveryStatus.DELIVERED);
                notification.setDeliveredAt(now);
            }
            case "bounced" -> {
                notification.setDeliveryStatus(DeliveryStatus.BOUNCED);
                notification.setBouncedAt(now);
            }
            case "opened" -> {
                notification.setDeliveryStatus(DeliveryStatus.OPENED);
                notification.setOpenedAt(now);
            }
            case "clicked" -> {
                notification.setDeliveryStatus(DeliveryStatus.CLICKED);
                notification.setClickedAt(now);
            }
            case "complained" -> {
                notification.setDeliveryStatus(DeliveryStatus.COMPLAINED);
                notification.setBouncedAt(now);
            }
        }
    }
}
