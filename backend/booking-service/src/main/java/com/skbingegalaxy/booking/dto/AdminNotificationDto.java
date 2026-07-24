package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminNotificationDto {
    private Long id;
    private String type;
    private String severity;
    private String title;
    private String message;
    private Long relatedBingeId;
    private String actionUrl;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    // ── Messaging ──
    private Long senderUserId;
    private String senderRole;
    private String senderName;
    private Long recipientUserId;
    private String recipientRole;
    private String recipientName;
    private Long threadId;
    private Long parentId;
    /** True when the current caller authored this message (drives left/right bubble in the UI). */
    private Boolean mine;
    /** True for platform notifications (senderRole == SYSTEM) — not repliable. */
    private Boolean system;
    // ── Attachment ──
    private String attachmentUrl;
    private String attachmentType;  // image | video
    private String attachmentName;
}
