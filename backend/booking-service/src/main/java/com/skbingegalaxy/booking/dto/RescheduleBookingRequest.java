package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RescheduleBookingRequest {

    @NotNull(message = "New booking date is required")
    private LocalDate newBookingDate;

    @NotNull(message = "New start time is required")
    private LocalTime newStartTime;

    /** Duration in minutes (30-min granularity). If null, keeps the original duration. */
    @Min(value = 30, message = "Duration must be at least 30 minutes")
    @Max(value = 720, message = "Duration cannot exceed 12 hours")
    private Integer newDurationMinutes;
}
