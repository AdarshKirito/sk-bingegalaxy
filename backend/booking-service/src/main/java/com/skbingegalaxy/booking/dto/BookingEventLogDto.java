package com.skbingegalaxy.booking.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class BookingEventLogDto {
    private Long id;
    private String bookingRef;
    private String eventType;
    private String previousStatus;
    private String newStatus;
    private Long triggeredBy;
    private String triggeredByRole;
    private String triggeredByName;
    private String description;
    private String snapshot;
    private String reason;
    private String ipAddress;
    private String userAgent;
    private Long bingeId;
    private int eventVersion;
    private LocalDateTime createdAt;
}
