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

    /** Optional venue room selection for all occurrences. */
    private Long venueRoomId;

    @NotNull(message = "Recurrence pattern is required")
    private RecurrencePattern pattern;

    /** Number of occurrences to create (2-52). */
    @Min(value = 2, message = "At least 2 occurrences required")
    @Max(value = 52, message = "Maximum 52 occurrences")
    private int occurrences;

    /*
     * Attribution (distribution G-B), mirroring CreateBookingRequest.
     *
     * A recurring series is a SECOND write path into bookings, and it previously built
     * its rows without these fields — so a customer arriving from a Google deep link who
     * chose "repeat weekly" produced bookings with no attribution at all. The channel
     * would then under-report by exactly the customers who committed hardest to it,
     * which is the worst possible direction for a number meant to justify building it.
     *
     * Structurally the same mistake as the binge grace-period defect: a field that one
     * write path sets and another silently does not.
     *
     * Deliberately NOT @Size-constrained, for the same reason as CreateBookingRequest —
     * the controller bean-validates, so a bound here would reject a whole series of real
     * bookings over a marketing parameter the customer never typed.
     */
    private String attributionSource;

    private String attributionRef;

    private java.time.LocalDateTime attributionCapturedAt;

    public enum RecurrencePattern {
        WEEKLY,
        BIWEEKLY,
        MONTHLY
    }
}
