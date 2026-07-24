package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Loyalty v2 — per-country earn/redeem economics, managed by the super admin
 * in the Loyalty Center.
 *
 * <p>Points are a single platform-wide unit, but a "10 pts per 1.00" rule means
 * wildly different real value in INR vs USD. This table defines, per country:
 * how much LOCAL currency earns points ({@code pointsNumerator} per
 * {@code amountDenominator} units) and how many points equal one local
 * currency unit at redemption ({@code pointsPerCurrencyUnit}) — so a point is
 * worth roughly the same everywhere, Bonvoy-style.
 *
 * <p>Consumed by {@code LoyaltyBindingAutoSeeder} when a new binge gets its
 * default earn/redeem rules. Countries without a row fall back to the INR
 * baseline. Editing a country config does NOT retro-change existing binge
 * rules (those are effective-dated per binge); it changes what NEW binges are
 * seeded with — per-binge rules remain the override point.
 */
@Entity
@Table(name = "loyalty_country_earn_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyCountryEarnConfig {

    /** ISO-3166 alpha-2, e.g. "US". */
    @Id
    @Column(name = "country_iso2", length = 2)
    private String countryIso2;

    @Column(name = "currency_code", nullable = false, length = 8)
    private String currencyCode;

    @Column(name = "points_numerator", nullable = false)
    private long pointsNumerator;

    @Column(name = "amount_denominator", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountDenominator;

    /** Redemption: points required per 1 unit of local currency. */
    @Column(name = "points_per_currency_unit", nullable = false)
    private long pointsPerCurrencyUnit;

    @Column(name = "min_redemption_points", nullable = false)
    @Builder.Default
    private long minRedemptionPoints = 100L;

    @Column(name = "max_redemption_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal maxRedemptionPercent = new BigDecimal("50.00");

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(length = 300)
    private String notes;

    @Column(name = "updated_by_admin_id")
    private Long updatedByAdminId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
