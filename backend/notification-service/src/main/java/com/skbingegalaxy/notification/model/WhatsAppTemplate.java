package com.skbingegalaxy.notification.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Maps notification types to pre-approved Twilio Content SIDs for WhatsApp.
 * Meta/WhatsApp requires business-initiated messages to use pre-approved templates.
 * This model stores the mapping from our internal type keys to Twilio Content SIDs.
 */
@Document(collection = "whatsapp_templates")
@CompoundIndex(name = "idx_template_name", def = "{'templateName': 1}", unique = true)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class WhatsAppTemplate {

    @Id
    private String id;

    /** Our internal notification type key (e.g. "BOOKING_CREATED"). */
    private String templateName;

    /** Twilio Content SID (e.g. "HXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"). */
    private String contentSid;

    /** Human-readable description. */
    private String description;

    /** Whether this template mapping is active. */
    @Builder.Default
    private boolean active = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
