package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — binge-specific reward inventory.
 * <p>Unlike platform perks these are goods/services the binge itself
 * supplies: free dessert, 1-hr free session, merchandise, etc.
 * {@code inventoryRemaining = null} means unlimited.
 */
@Entity
@Table(name = "loyalty_binge_reward_item")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyBingeRewardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "binding_id", nullable = false)
    private Long bindingId;

    @Column(nullable = false, length = 60)
    private String sku;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(name = "point_cost", nullable = false)
    private long pointCost;

    @Column(name = "min_tier_code", length = 30)
    private String minTierCode;

    @Column(name = "inventory_remaining")
    private Long inventoryRemaining;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
