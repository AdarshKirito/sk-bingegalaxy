package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringBookingRequest {

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "Duration is required")
    @Min(value = 30, message = "Duration must be at least 30 minutes")
    @Max(value = 720, message = "Duration cannot exceed 12 hours")
    private Integer durationMinutes;

    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    @Builder.Default
    private int numberOfGuests = 1;

    @Valid
    private List<AddOnSelection> addOns;

    @Size(max = 1000, message = "Special notes limited to 1000 characters")
    private String specialNotes;

    @NotNull(message = "Recurrence pattern is required")
    private RecurrencePattern pattern;

    /** Number of occurrences to create (2-52). */
    @Min(value = 2, message = "At least 2 occurrences required")
    @Max(value = 52, message = "Maximum 52 occurrences")
    private int occurrences;

    public enum RecurrencePattern {
        WEEKLY,
        BIWEEKLY,
        MONTHLY
    }
}
