package com.skbingegalaxy.notification.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A single browser Web Push subscription (one per device/browser profile).
 *
 * <p>Created when a signed-in user grants notification permission in the PWA. The
 * {@code endpoint} is the push-service URL the browser vendor (FCM/Mozilla/Apple)
 * gave us; {@code p256dh} + {@code auth} are the client keys used to encrypt the
 * RFC-8291 payload. A user may hold several subscriptions (phone + laptop, …), so
 * fan-out is keyed on {@link #recipientEmail}. The {@code endpoint} is unique — the
 * same browser re-subscribing upserts rather than duplicating.
 */
@Document(collection = "push_subscriptions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PushSubscription {

    @Id
    private String id;

    /** Push-service endpoint URL. Unique per browser subscription. */
    @Indexed(unique = true)
    private String endpoint;

    /** Owner's email — the fan-out key (matches Notification.recipientEmail). */
    @Indexed
    private String recipientEmail;

    /** Owner's numeric user id (from X-User-Id) for auditing / future per-user views. */
    private Long userId;

    /** Client public key (base64url) — ECDH peer for payload encryption. */
    private String p256dh;

    /** Client auth secret (base64url). */
    private String auth;

    /** User-agent string of the subscribing browser (diagnostics only). */
    private String userAgent;

    /** Consecutive delivery failures; a 404/410 prunes the row immediately. */
    @Builder.Default
    private int failureCount = 0;

    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
}
