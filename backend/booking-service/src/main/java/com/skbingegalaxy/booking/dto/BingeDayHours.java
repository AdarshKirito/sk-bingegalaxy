package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Operating hours for a single day of the week within a binge's per-day schedule.
 *
 * <p>{@link #dayOfWeek} follows {@link java.time.DayOfWeek}: 1 = Monday … 7 = Sunday.
 * When {@link #closed} is true the venue takes no bookings that day and the times are
 * ignored. Times are local to the binge timezone and serialized as {@code HH:mm}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeDayHours {

    /** 1 = Monday … 7 = Sunday (java.time.DayOfWeek). */
    private Integer dayOfWeek;

    /** When true, the venue is closed this day and takes no bookings. */
    private boolean closed;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime openTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime closeTime;
}
