package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for {@code POST /api/v1/bookings/slot-holds}. Captures the slot
 * the customer wants to lock while they enter payment details. The hold's
 * fields are later validated against {@code CreateBookingRequest} so a
 * customer can't quietly mutate the slot after acquiring the hold.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSlotHoldRequest {

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
    private LocalDate bookingDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @Min(value = 30, message = "Duration must be at least 30 minutes")
    @Max(value = 720, message = "Duration cannot exceed 12 hours")
    private int durationMinutes;

    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    @Builder.Default
    private int numberOfGuests = 1;

    private Long venueRoomId;
}
