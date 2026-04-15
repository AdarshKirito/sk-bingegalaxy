package com.skbingegalaxy.notification.dto;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.model.DeliveryStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationDto {

    private String id;
    private String recipientEmail;
    private String recipientPhone;
    private String recipientName;
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
    private LocalDateTime createdAt;

    // Delivery tracking
    private DeliveryStatus deliveryStatus;
    private LocalDateTime deliveredAt;
    private LocalDateTime openedAt;
    private LocalDateTime clickedAt;
    private LocalDateTime bouncedAt;

    private LocalDateTime nextRetryAt;
}
