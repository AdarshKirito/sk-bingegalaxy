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

    // ── Structured address (optional but recommended) ──
    @Size(max = 200) private String addressLine1;
    @Size(max = 200) private String addressLine2;
    @Size(max = 100) private String city;
    @Size(max = 100) private String state;
    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "Country must be an ISO-3166-1 alpha-2 code (e.g. IN, US)")
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
     * V56: when {@code true}, customers must select a venue room during booking.
     * Defaults to {@code false} (room selection optional). Null leaves the
     * existing value unchanged on update.
     */
    private Boolean roomSelectionRequired;
}
