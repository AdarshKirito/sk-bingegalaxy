package com.skbingegalaxy.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

/**
 * Service-to-service binge snapshot served by
 * {@code GET /api/v1/bookings/internal/binges/{id}} (X-Internal-Secret protected).
 *
 * <p>This is the ONLY contract other services may use to verify binge ownership.
 * The public {@code /binges/{id}} endpoint deliberately strips {@code adminId}
 * (privacy) — deserializing ownership from it silently yields {@code null} and
 * makes every ownership check fail with 403. Payment- and availability-service
 * scope checks were bitten by exactly that; do not point them back at the
 * public endpoint.</p>
 *
 * <p>Unlike the public DTO this returns binges in ANY approval state — admins
 * must be able to manage venues that are still pending approval or deactivated.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalBingeDto {
    private Long id;
    private Long adminId;
    private boolean active;
    /** {@link com.skbingegalaxy.booking.entity.BingeApprovalStatus} name. */
    private String status;
    /** IANA timezone id, e.g. "Asia/Kolkata". */
    private String timezone;
    /** ISO-4217 currency code derived from the venue country. */
    private String currency;
    /**
     * ISO-3166-1 alpha-2 country code. Mandatory since V79 (legacy NULLs were
     * backfilled from currency), so consumers may rely on it being present —
     * it is what payment-service resolves a venue's payment methods from.
     */
    private String country;
    private LocalTime openTime;
    private LocalTime closeTime;
    private List<BingeDayHours> openingHours;
    /**
     * Module keys the requesting user (the {@code userId} query param) may NOT
     * use in this binge — V71 permission matrix. Null when no userId was sent.
     * Lets payment-/availability-service enforce DISPUTES / FAILED_REFUNDS /
     * BLOCKED_DATES without an extra round trip.
     */
    private List<String> deniedModules;
}
