package com.skbingegalaxy.notification.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A versioned notification template stored in MongoDB.
 * Templates are identified by (name, channel, version) and can be toggled active/inactive.
 */
@Document(collection = "notification_templates")
@CompoundIndex(name = "idx_name_channel_version", def = "{'name': 1, 'channel': 1, 'version': 1}", unique = true)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationTemplate {

    @Id
    private String id;

    /** Template key matching notification types (e.g. "BOOKING_CREATED"). */
    private String name;

    /** Which channel this template is for (e.g. "EMAIL", "SMS", "WHATSAPP"). */
    private String channel;

    /** Version number — monotonically increasing per (name, channel). */
    private int version;

    /** The template content (HTML for email, plain text for SMS/WhatsApp). */
    private String content;

    /** Subject line (for email templates). */
    private String subject;

    /** Whether this version is the currently active one. */
    @Builder.Default
    private boolean active = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
