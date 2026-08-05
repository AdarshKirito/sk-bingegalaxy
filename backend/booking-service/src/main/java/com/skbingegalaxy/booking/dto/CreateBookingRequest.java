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
     * Optional pre-payment slot-hold token from POST /bookings/slot-holds.
     * When supplied, the hold is validated (ownership, matching slot) and
     * consumed atomically inside the booking transaction, and the hold's
     * reservation transfers to the booking. Without it, the booking is a
     * direct booking and must win the slot against any live foreign holds.
     */
    @Size(max = 64, message = "Hold token too long")
    private String holdToken;

    /**
     * Marketing source captured on the landing page, e.g. {@code google_things_to_do}
     * (distribution design G-B). Carried by the client through the wizard.
     *
     * <p><b>Reporting only.</b> These three fields reach the persisted booking and
     * nothing else — no price, no availability, no eligibility decision reads them.
     * The bound is important on its own: this is customer-supplied data on a public
     * endpoint, and the columns are VARCHAR(64)/VARCHAR(128), so an unbounded value
     * would be a write-amplification vector rather than merely a bad label.
     */
    /*
     * DELIBERATELY NOT @Size-CONSTRAINED, unlike every other string on this request.
     *
     * The controller takes `@Valid @RequestBody`, so a @Size violation rejects the WHOLE
     * booking with 400 before any service code runs. On these two fields that would mean
     * an over-long utm_source -- data the CUSTOMER never typed and cannot fix -- killing
     * a real sale at the final step, on the very channel this feature exists to measure.
     * That contradicts the rule the rest of this feature is built on: losing an analytics
     * dimension is acceptable, losing the sale is not.
     *
     * Bounded elsewhere, twice: BookingAttribution truncates to the column widths before
     * persistence, and the request as a whole is already capped by Tomcat's
     * max-http-request-header-size / max-http-form-post-size. So dropping the annotation
     * removes a failure mode without opening an unbounded write.
     */
    private String attributionSource;

    private String attributionRef;

    /**
     * When the referral was captured in the browser. Untrusted — it is the client's own
     * clock — so the server treats a future value as expired rather than as valid
     * forever. See {@code BookingAttribution}.
     */
    private java.time.LocalDateTime attributionCapturedAt;

}
