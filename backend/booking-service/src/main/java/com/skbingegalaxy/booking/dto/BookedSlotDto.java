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

    /**
     * V81 occupancy window — what the resource is ACTUALLY unavailable for, including
     * the booking's snapshotted setup/cleanup buffers. {@code startMinute}/{@code durationMinutes}
     * remain the billable interval so existing display code is unaffected.
     *
     * <p>The wizard must grey out slots against these fields, not the billable ones:
     * offering a slot inside another booking's turnover time renders a picker whose
     * choices the server will reject at checkout.
     *
     * <p>May be negative / exceed 1440 when a buffer crosses midnight.
     */
    private int occupancyStartMinute;
    private int occupancyEndMinute;

    private String bookingRef;
    /**
     * The venue room this booking occupies (null for a room-less venue). Lets the booking
     * wizard compute per-room availability: a time is only "booked" for a specific room
     * when that room has an overlapping booking, and only fully unavailable ("any room")
     * when the count of overlapping bookings reaches the number of rooms.
     */
    private Long venueRoomId;
}
