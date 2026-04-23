package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** Maps which perks each tier gets by default, with optional cost override. */
@Entity
@Table(name = "loyalty_tier_perk")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyTierPerk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "tier_definition_id", nullable = false)
    private Long tierDefinitionId;

    @Column(name = "perk_id", nullable = false)
    private Long perkId;

    @Column(name = "override_point_cost")
    private Long overridePointCost;

    @Column(name = "auto_grant", nullable = false)
    @Builder.Default
    private boolean autoGrant = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
