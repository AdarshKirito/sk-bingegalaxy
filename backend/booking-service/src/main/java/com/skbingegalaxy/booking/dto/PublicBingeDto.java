package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalTime;

/**
 * Customer-facing projection of a {@link com.skbingegalaxy.booking.entity.Binge}.
 *
 * <p>Returned by the <b>anonymous</b> endpoints ({@code GET /binges},
 * {@code /binges/nearby}, {@code /binges/{id}}). It deliberately omits every
 * operational / lifecycle / anti-abuse field that the full {@link BingeDto}
 * carries — owner {@code adminId}, super-admin approval audit (decided-by, decided-at,
 * rejection reason), grace-period bookkeeping, and the freeze (anti-abuse) thresholds.
 * Those are admin-only signals and must never reach an unauthenticated caller, where
 * they would leak internal user ids and reveal cancellation/no-show thresholds an
 * abuser could game.
 *
 * <p>The fields kept here are exactly what the customer venue selector, platform
 * dashboard, and the persisted "selected venue" need: identity, address, geo, support
 * contacts, timezone, operating hours, and the customer-cancellation knobs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicBingeDto {
    private Long id;
    private String name;
    private String address;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String country;
    private String postalCode;

    /** WGS-84 latitude in decimal degrees; null when the venue is not geocoded. */
    private Double latitude;
    /** WGS-84 longitude in decimal degrees; null when the venue is not geocoded. */
    private Double longitude;
    /** Distance in km from the query point; only populated by {@code /binges/nearby}. */
    private Double distanceKm;

    /** IANA timezone for this venue, e.g. "Asia/Kolkata". */
    private String timezone;

    private String supportEmail;
    private String supportPhone;
    private String supportPhoneCountryCode;
    private String supportWhatsapp;
    private String supportWhatsappCountryCode;

    private boolean customerCancellationEnabled;
    private int customerCancellationCutoffMinutes;
    private Integer maxConcurrentBookings;

    private LocalTime openTime;
    private LocalTime closeTime;

    /** When true, the customer must pick a venue room during booking. */
    private boolean roomSelectionRequired;
}
