package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — per-binge override of a platform perk.
 * Modes: INHERIT (use catalog default), DISABLED (perk hidden for this binge),
 * OVERRIDDEN (use override fields).
 */
@Entity
@Table(name = "loyalty_binge_perk_override")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyBingePerkOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "binding_id", nullable = false)
    private Long bindingId;

    @Column(name = "perk_id", nullable = false)
    private Long perkId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String mode = "INHERIT";

    @Column(name = "override_point_cost")
    private Long overridePointCost;

    @Column(name = "override_cooldown_hours")
    private Integer overrideCooldownHours;

    @Column(name = "override_params_json", columnDefinition = "TEXT")
    private String overrideParamsJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
