package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.notification.model.DeliveryStatus;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook endpoint for receiving delivery status callbacks from
 * email providers (SendGrid, Mailgun, SES, etc.).
 *
 * <p>The endpoint is unauthenticated so external providers can POST to it.
 * Security relies on the webhook-signing verification of each provider,
 * which should be added per-provider as needed.
 */
@RestController
@RequestMapping("/api/v1/notifications/webhooks")
@RequiredArgsConstructor
@Slf4j
public class DeliveryWebhookController {

    private final NotificationRepository notificationRepository;

    /**
     * Generic delivery event webhook.
     * Expects JSON with at least: {@code notificationId} and {@code event}.
     * Supported events: delivered, bounced, opened, clicked, complained.
     */
    @PostMapping("/delivery")
    public ResponseEntity<Void> handleDeliveryEvent(@RequestBody Map<String, String> payload) {
        String notificationId = payload.get("notificationId");
        String event = payload.get("event");

        if (notificationId == null || event == null) {
            log.warn("Delivery webhook missing notificationId or event");
            return ResponseEntity.badRequest().build();
        }

        return notificationRepository.findById(notificationId)
            .map(notification -> {
                updateDeliveryStatus(notification, event);
                notificationRepository.save(notification);
                log.info("Delivery webhook processed: id={} event={}", notificationId, event);
                return ResponseEntity.ok().<Void>build();
            })
            .orElseGet(() -> {
                log.warn("Delivery webhook for unknown notification: {}", notificationId);
                return ResponseEntity.notFound().build();
            });
    }

    private void updateDeliveryStatus(Notification notification, String event) {
        LocalDateTime now = LocalDateTime.now();
        switch (event.toLowerCase()) {
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
            default -> log.warn("Unknown delivery event type: {}", event);
        }
    }
}
