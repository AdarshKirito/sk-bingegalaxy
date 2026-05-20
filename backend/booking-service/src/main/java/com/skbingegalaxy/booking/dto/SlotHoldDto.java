package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotHoldDto {
    private Long id;
    private String holdToken;
    private Long bingeId;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private Long eventTypeId;
    private String eventTypeName;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationMinutes;
    private int numberOfGuests;
    private Long venueRoomId;
    private String status;
    private LocalDateTime expiresAt;
    /** Server-derived seconds remaining (defensive — UI uses expiresAt for the live ticker). */
    private long secondsRemaining;
    private String convertedBookingRef;
    private String releaseReason;
    private LocalDateTime createdAt;
}
