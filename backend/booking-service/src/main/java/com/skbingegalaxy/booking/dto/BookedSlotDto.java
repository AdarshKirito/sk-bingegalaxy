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
    /**
     * The venue room this booking occupies (null for a room-less venue). Lets the booking
     * wizard compute per-room availability: a time is only "booked" for a specific room
     * when that room has an overlapping booking, and only fully unavailable ("any room")
     * when the count of overlapping bookings reaches the number of rooms.
     */
    private Long venueRoomId;
}
