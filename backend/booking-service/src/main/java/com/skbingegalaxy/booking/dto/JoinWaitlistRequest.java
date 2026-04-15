package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinWaitlistRequest {

    @NotNull(message = "Event type is required")
    private Long eventTypeId;

    @NotNull(message = "Preferred date is required")
    private LocalDate preferredDate;

    @NotNull(message = "Preferred start time is required")
    private LocalTime preferredStartTime;

    @Min(value = 30, message = "Duration must be at least 30 minutes")
    private int durationMinutes = 60;

    @Min(value = 1, message = "Number of guests must be at least 1")
    private int numberOfGuests = 1;
}
