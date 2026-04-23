package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Loyalty v2 — per-binge earn rule.
 *
 * <p>Multiple rows may exist per binding (tier-specific overrides,
 * time-bounded campaigns, bonus windows).  The resolver picks the most
 * specific active rule at earn time — specificity order: tier-specific
 * active campaign → tier-specific baseline → universal campaign →
 * universal baseline.
 *
 * <p>{@code points = floor(amount × pointsNumerator / amountDenominator)
 * × tierMultiplier}, then capped at {@code capPerBooking}.
 * {@code qcMultiplier} scales the qualifying-credit yield separately from
 * the redeemable-point yield — this is how a binge can be marked "Luxury"
 * (Bonvoy-style) and deliver 1.5× QC while keeping normal point earn.
 */
@Entity
@Table(name = "loyalty_binge_earning_rule")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyBingeEarningRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "binding_id", nullable = false)
    private Long bindingId;

    /** NULL = applies to all tiers. */
    @Column(name = "tier_code", length = 30)
    private String tierCode;

    /** FLAT_PER_AMOUNT / CATEGORY_MULTIPLIER / BONUS_WINDOW */
    @Column(name = "rule_type", nullable = false, length = 30)
    @Builder.Default
    private String ruleType = "FLAT_PER_AMOUNT";

    @Column(name = "points_numerator", nullable = false)
    private long pointsNumerator;

    @Column(name = "amount_denominator", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountDenominator;

    @Column(name = "tier_multiplier", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal tierMultiplier = BigDecimal.ONE;

    @Column(name = "qc_multiplier", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal qcMultiplier = BigDecimal.ONE;

    @Column(name = "min_booking_amount", precision = 12, scale = 2)
    private BigDecimal minBookingAmount;

    @Column(name = "cap_per_booking")
    private Long capPerBooking;

    @Column(name = "daily_velocity_cap")
    private Long dailyVelocityCap;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
