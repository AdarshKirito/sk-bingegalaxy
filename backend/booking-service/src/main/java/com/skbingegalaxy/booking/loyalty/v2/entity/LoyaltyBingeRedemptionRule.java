package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Loyalty v2 — per-binge redemption (burn) rule.
 *
 * <p>{@code tierBonusPctJson} is a JSON map like
 * <code>{"GOLD":5,"PLATINUM":10}</code> meaning Gold members get a 5%
 * better redemption value at this binge, Platinum 10%.  Keeps tier-
 * specific rates sparse and trivially editable without a schema-change.
 */
@Entity
@Table(name = "loyalty_binge_redemption_rule")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyBingeRedemptionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "binding_id", nullable = false)
    private Long bindingId;

    @Column(name = "points_per_currency_unit", nullable = false)
    private long pointsPerCurrencyUnit;

    @Column(name = "min_redemption_points", nullable = false)
    @Builder.Default
    private long minRedemptionPoints = 0L;

    @Column(name = "max_redemption_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal maxRedemptionPercent = new BigDecimal("100.00");

    @Column(name = "tier_bonus_pct_json", columnDefinition = "TEXT")
    private String tierBonusPctJson;

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
