package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@JsonIgnoreProperties(ignoreUnknown = false)
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

    /** Optional free-text reason for the reschedule. Captured in the audit trail. */
    @Size(max = 1000, message = "Reason must be 1000 characters or fewer")
    private String reason;
}
