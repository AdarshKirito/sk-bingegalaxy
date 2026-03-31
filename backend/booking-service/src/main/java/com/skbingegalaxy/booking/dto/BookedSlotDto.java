package com.skbingegalaxy.booking.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookedSlotDto {
    private int startHour;
    private int durationHours;
    private int startMinute;      // minutes from midnight (e.g. 600 = 10:00, 630 = 10:30)
    private int durationMinutes;  // duration in minutes (e.g. 90 = 1.5 hrs)
    private String bookingRef;
}
