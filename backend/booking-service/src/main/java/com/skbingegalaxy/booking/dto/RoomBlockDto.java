package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomBlockDto {
    private Long id;
    private Long roomId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
    /**
     * Advisory, set on CREATE only: active bookings already overlapping the
     * blocked window. Blocks never cancel bookings — ops must reschedule these
     * by hand, so the UI surfaces the count as a warning.
     */
    private Integer affectedBookings;
}
