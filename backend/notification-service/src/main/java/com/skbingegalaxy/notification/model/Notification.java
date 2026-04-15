package com.skbingegalaxy.notification.model;

import com.skbingegalaxy.common.enums.NotificationChannel;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "notifications")
@CompoundIndex(name = "idx_recipient_type", def = "{'recipientEmail': 1, 'type': 1}")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Notification {

    @Id
    private String id;

    @Indexed
    private String recipientEmail;

    private String recipientPhone;

    private String recipientName;

    @Indexed
    private String type;

    private NotificationChannel channel;

    private String subject;

    private String body;

    private Map<String, Object> metadata;

    private String bookingRef;

    private boolean sent;

    private String failureReason;

    private int retryCount;

    private LocalDateTime sentAt;

    @CreatedDate
    private LocalDateTime createdAt;

    // ── Delivery tracking ──

    @Builder.Default
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    private LocalDateTime deliveredAt;
    private LocalDateTime openedAt;
    private LocalDateTime clickedAt;
    private LocalDateTime bouncedAt;

    // ── Exponential backoff ──

    /** When the next retry should be attempted (null = immediate / not scheduled). */
    @Indexed
    private LocalDateTime nextRetryAt;

    // ── Batching / Digest ──

    /** Group key for digest aggregation (e.g. "digest:<email>"). Null = non-digest. */
    @Indexed
    private String digestGroup;

    /** Whether this notification has been folded into a digest email already. */
    @Builder.Default
    private boolean digested = false;
}
