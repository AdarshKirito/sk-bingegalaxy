package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitlistEntryDto {
    private Long id;
    private Long bingeId;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private EventTypeDto eventType;
    private LocalDate preferredDate;
    private LocalTime preferredStartTime;
    private int durationMinutes;
    private int numberOfGuests;
    private String status;
    private int position;
    private LocalDateTime offerExpiresAt;
    private LocalDateTime notifiedAt;
    private String convertedBookingRef;
    private LocalDateTime createdAt;
}
