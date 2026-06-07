package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Strict mode: reject any unknown field to prevent mass-assignment probing.
// A customer POSTing {"eventTypeId":1,"loyaltyTier":"GOLD"} gets 400, not silent ignore.
@JsonIgnoreProperties(ignoreUnknown = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBookingRequest {

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
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

    @Valid
    private List<AddOnSelection> addOns;

    @Size(max = 1000, message = "Special notes limited to 1000 characters")
    private String specialNotes;

    /** Optional venue room ID for seat/room selection. */
    private Long venueRoomId;

    /** Number of loyalty points the customer wants to redeem as discount. */
    @Min(value = 0, message = "Loyalty points to redeem cannot be negative")
    private Long redeemLoyaltyPoints;

    /**
     * Optional FX rate lock token obtained from POST /checkout/lock-fx.
     * When supplied, the locked rate is validated and consumed atomically at
     * booking creation — if the lock has expired the request is rejected with
     * 400 "FX rate has expired, please refresh your quote".
     * International customers should always supply this to guarantee price
     * stability between the checkout preview and payment.
     */
    @Size(max = 64, message = "FX lock token too long")
    private String fxLockToken;
}
