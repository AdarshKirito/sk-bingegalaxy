package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BingeSaveRequest {

    @NotBlank(message = "Binge name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 500)
    private String address;

    // ── Structured address (street/city optional but recommended; country is not) ──
    @Size(max = 200) private String addressLine1;
    @Size(max = 200) private String addressLine2;
    @Size(max = 100) private String city;
    @Size(max = 100) private String state;

    /**
     * REQUIRED. The venue's country is load-bearing rather than descriptive: it
     * derives the venue's {@code currency}, seeds its timezone, selects its tax
     * rules, and decides which payment methods customers are offered at checkout.
     * A venue without one silently inherits platform defaults (INR / Asia-Kolkata /
     * card-only), which is wrong for every venue outside India.
     *
     * <p>Legacy rows predating this rule are backfilled from their currency by
     * migration {@code V79__binge_country_required}.
     */
    @NotBlank(message = "Venue country is required — it sets the venue's currency, timezone, taxes and payment methods")
    @Pattern(regexp = "^[A-Z]{2}$", message = "Country must be an ISO-3166-1 alpha-2 code (e.g. IN, US)")
    private String country;
    @Pattern(regexp = "^$|^[A-Za-z0-9 \\-]{3,20}$", message = "Postal code must be 3-20 alphanumeric characters")
    private String postalCode;

    /**
     * WGS-84 latitude in decimal degrees. Optional; when supplied (with {@link #longitude})
     * the venue becomes discoverable through the "venues near me" proximity ranking.
     * Both coordinates must be provided together — see {@code BingeService} validation.
     */
    @DecimalMin(value = "-90", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90", message = "Latitude must be between -90 and 90")
    private Double latitude;

    /** WGS-84 longitude in decimal degrees. See {@link #latitude}. */
    @DecimalMin(value = "-180", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180", message = "Longitude must be between -180 and 180")
    private Double longitude;

    /**
     * IANA timezone for this venue. Must be a valid {@link java.time.ZoneId} string
     * (e.g. "Asia/Kolkata", "America/New_York", "Europe/London", "Asia/Dubai").
     * When omitted the platform default ({@code app.business.default-timezone}) applies.
     */
    @Size(max = 64)
    private String timezone;

    @Size(max = 150)
    private String supportEmail;

    @Size(max = 20)
    @Pattern(regexp = "^$|^[+0-9][0-9\\s-]{6,19}$", message = "Support phone must be a valid phone number")
    private String supportPhone;

    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "Support phone country code must look like '+91'")
    private String supportPhoneCountryCode;

    @Size(max = 20)
    @Pattern(regexp = "^$|^[0-9]{7,20}$", message = "WhatsApp number must contain digits only")
    private String supportWhatsapp;

    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "WhatsApp country code must look like '+91'")
    private String supportWhatsappCountryCode;

    /** V78: the public support phone doubles as the WhatsApp contact. */
    private Boolean supportPhoneIsWhatsapp;

    // ── V78: personal/owner contact (super-admin → venue-admin channel; never public) ──
    @jakarta.validation.constraints.Email(message = "Owner email must be a valid email address")
    @Size(max = 150)
    private String ownerEmail;

    @Size(max = 20)
    @Pattern(regexp = "^$|^[+0-9][0-9\\s-]{6,19}$", message = "Owner phone must be a valid phone number")
    private String ownerPhone;

    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "Owner phone country code must look like '+91'")
    private String ownerPhoneCountryCode;

    /** Whether the owner phone is also reachable on WhatsApp. */
    private Boolean ownerPhoneIsWhatsapp;

    private Boolean customerCancellationEnabled;

    @PositiveOrZero(message = "Cancellation cutoff must be zero or more minutes")
    private Integer customerCancellationCutoffMinutes;

    @jakarta.validation.constraints.Min(value = 1, message = "Max concurrent bookings must be at least 1")
    private Integer maxConcurrentBookings;

    /**
     * Per-binge opening time (local). Null leaves the existing value unchanged on update,
     * or applies the global {@code app.theater.opening-hour} fallback on create.
     * Must be strictly less than {@link #closeTime} when both are provided.
     */
    private LocalTime openTime;

    /** Per-binge closing time (local). Null behaves the same as {@link #openTime}. */
    private LocalTime closeTime;

    /**
     * V81 venue-wide default prep time reserved BEFORE every booking, in minutes.
     * Individual event types may override it; a NULL override there inherits this.
     * Null here leaves the current value unchanged (0 for a new venue).
     */
    @jakarta.validation.constraints.Min(value = 0, message = "Default setup minutes cannot be negative")
    @jakarta.validation.constraints.Max(value = 240, message = "Default setup minutes cannot exceed 240 (4 hours)")
    private Integer defaultSetupMinutes;

    /**
     * V81 venue-wide default turnover time reserved AFTER every booking, in minutes.
     * This is the knob that stops back-to-back sales the venue cannot physically
     * reset for. See {@link #defaultSetupMinutes}.
     */
    @jakarta.validation.constraints.Min(value = 0, message = "Default cleanup minutes cannot be negative")
    @jakarta.validation.constraints.Max(value = 240, message = "Default cleanup minutes cannot exceed 240 (4 hours)")
    private Integer defaultCleanupMinutes;

    /**
     * V84 (G5): minimum lead time in minutes before a booking may start. 0 allows
     * same-minute booking. Null leaves the current value unchanged.
     */
    @jakarta.validation.constraints.Min(value = 0, message = "Minimum notice cannot be negative")
    @jakarta.validation.constraints.Max(value = 43200, message = "Minimum notice cannot exceed 30 days")
    private Integer minNoticeMinutes;

    /**
     * V84 (G5): how far ahead this venue publishes availability, in days. Null
     * leaves the current value unchanged; clearing it back to the platform default
     * is a super-admin operation.
     */
    @jakarta.validation.constraints.Min(value = 1, message = "Advance window must be at least 1 day")
    @jakarta.validation.constraints.Max(value = 730, message = "Advance window cannot exceed 730 days")
    private Integer maxAdvanceDays;

    /**
     * Optional per-day operating hours (overrides {@link #openTime}/{@link #closeTime}
     * for the matching day). When null, the schedule is left unchanged on update /
     * absent on create. An explicit empty list clears any existing per-day schedule.
     * {@code dayOfWeek} is 1=Mon..7=Sun; each open day must have close &gt; open.
     */
    @jakarta.validation.Valid
    private java.util.List<BingeDayHours> openingHours;

    /**
     * V56: when {@code true}, customers must select a venue room during booking.
     * Defaults to {@code false} (room selection optional). Null leaves the
     * existing value unchanged on update.
     */
    private Boolean roomSelectionRequired;
}
