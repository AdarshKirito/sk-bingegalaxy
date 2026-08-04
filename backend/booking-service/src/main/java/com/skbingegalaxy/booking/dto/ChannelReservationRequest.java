package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A reservation delivered by an external sales channel (V85, gap G2).
 *
 * <p><b>Provider-neutral by construction.</b> There is no OTA vocabulary here and
 * no provider-specific field. {@code externalSource} is an opaque slug that
 * booking-service stores and never interprets — translating a given channel's
 * payload into this shape is the job of the distribution bounded context, which
 * does not exist yet. Booking-service must never learn what "Bókun" is.
 *
 * <p><b>Why a separate DTO from {@link CreateBookingRequest}.</b> That one derives
 * the guest from the caller's JWT; a channel guest has no SK account, so the guest
 * arrives in the payload. Overloading the customer DTO with optional identity
 * fields would make it possible for a customer request to assert someone else's
 * identity — a privilege-escalation shape we refuse to create.
 */
// Strict: reject unknown fields so a provider payload cannot be posted raw and
// have parts of it silently ignored.
@JsonIgnoreProperties(ignoreUnknown = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelReservationRequest {

    /**
     * Provider-neutral slug for the channel delivering this reservation.
     *
     * <p><b>Canonicalised on the way in</b> — see {@link #setExternalSource}. A channel
     * that identifies itself as {@code "ACME-Channel"} and one that says
     * {@code "acme-channel"} are the same channel; if both spellings could be stored,
     * the unique index on {@code (external_source, external_ref)} would hold two rows
     * and the redelivery check would miss, so a retry would double-book the venue.
     *
     * <p>Normalising in the setter rather than in the service matters: bean validation
     * runs against the field <em>after</em> deserialization, so validating the raw
     * value would 400 on {@code "ACME-Channel"} at the edge while the service happily
     * normalised the same input — the endpoint and the service would disagree about
     * what is acceptable.
     */
    @NotBlank(message = "externalSource is required for a channel reservation")
    @Size(max = 64, message = "externalSource must be 64 characters or fewer")
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$",
        message = "externalSource must be a slug of letters, digits, dot, dash or underscore")
    private String externalSource;

    /** Trims and lower-cases so validation and persistence both see the canonical form. */
    public void setExternalSource(String externalSource) {
        this.externalSource = canonicalSource(externalSource);
    }

    /**
     * The single definition of a canonical channel slug. Used by the setter and by
     * lookups, so no caller can accidentally query with an un-normalised value.
     */
    public static String canonicalSource(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The channel's own booking reference. Unique per source — a partial unique index
     * makes a redelivered reservation converge on the original rather than duplicating.
     *
     * <p>Trimmed but deliberately <b>NOT</b> lower-cased: provider references are
     * opaque, case-sensitive identifiers ({@code "ACME-1a"} and {@code "acme-1A"} may
     * legitimately be different bookings). Folding case here would merge two distinct
     * reservations into one — the opposite failure, and a worse one.
     */
    @NotBlank(message = "externalRef is required for a channel reservation")
    @Size(max = 128, message = "externalRef must be 128 characters or fewer")
    private String externalRef;

    /** Trims only; case is significant for a provider's own reference. */
    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef == null ? null : externalRef.trim();
    }

    /** Venue receiving the reservation. Explicit rather than header-derived: there is no user session here. */
    @NotNull(message = "bingeId is required")
    private Long bingeId;

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
    private LocalDate bookingDate;

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

    // ── Guest identity supplied by the channel ───────────────────────────
    // PII arriving from a third party. Length-capped and format-checked here so a
    // malformed provider payload cannot reach persistence.

    @NotBlank(message = "Guest name is required")
    @Size(max = 150)
    private String guestName;

    @Email(message = "Guest email must be valid")
    @Size(max = 150)
    private String guestEmail;

    @Size(max = 20)
    @Pattern(regexp = "^$|^\\d{4,15}$", message = "Guest phone must be 4-15 digits")
    private String guestPhone;

    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "Phone country code must look like '+91'")
    private String guestPhoneCountryCode;

    /** Optional specific room. Null lets the venue's own assignment rules pick one. */
    private Long venueRoomId;

    @Valid
    private List<AddOnSelection> addOns;

    @Size(max = 1000, message = "Notes limited to 1000 characters")
    private String specialNotes;
}
