package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBookingRequest {

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
    @FutureOrPresent(message = "Booking date must be today or in the future")
    private LocalDate bookingDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    private int durationHours;

    /** Duration in minutes (30-min granularity). Takes precedence over durationHours when set. */
    @Min(value = 30, message = "Duration must be at least 30 minutes")
    private Integer durationMinutes;

    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    @Builder.Default
    private int numberOfGuests = 1;

    private List<AddOnSelection> addOns;

    @Size(max = 1000, message = "Special notes limited to 1000 characters")
    private String specialNotes;
}
