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
}
