package com.skbingegalaxy.notification.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-user notification opt-out preferences.
 * By default every channel/type is enabled; entries in the muted sets disable them.
 */
@Document(collection = "notification_preferences")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    private String id;

    @Indexed(unique = true)
    private String recipientEmail;

    /** Notification type keys the user has opted out of (e.g. "BOOKING_CREATED", "PAYMENT_SUCCESS"). */
    @Builder.Default
    private Set<String> mutedTypes = new HashSet<>();

    /** Channels the user has opted out of entirely (e.g. "SMS", "WHATSAPP"). */
    @Builder.Default
    private Set<String> mutedChannels = new HashSet<>();

    /** Master kill-switch — if true, no notifications are sent. */
    @Builder.Default
    private boolean globalOptOut = false;

    private LocalDateTime updatedAt;
}
