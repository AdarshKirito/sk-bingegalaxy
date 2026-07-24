package com.skbingegalaxy.availability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Per-day operating hours for a binge, mirrored from booking-service's
 * {@code BingeDayHours} contract. {@code dayOfWeek} is 1=Monday..7=Sunday
 * ({@link java.time.DayOfWeek}). When {@code closed} is true the venue takes no
 * bookings that day and the times are ignored. Times are venue-local, {@code HH:mm}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BingeDayHoursDto {
    private Integer dayOfWeek;
    private boolean closed;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime openTime;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime closeTime;
}
