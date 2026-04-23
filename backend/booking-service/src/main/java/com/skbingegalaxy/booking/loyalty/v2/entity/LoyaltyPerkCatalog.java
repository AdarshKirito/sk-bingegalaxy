package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — platform-wide perk catalog.
 *
 * <p>Each catalog row references a named delivery handler ({@code
 * deliveryHandlerKey}) which resolves to a Spring bean implementing
 * {@code PerkDeliveryHandler}.  Adding a new perk = insert a row + drop
 * in a new {@code @PerkHandler("KEY")} component. Open-closed principle.
 *
 * <p>{@code fulfillmentType}:
 * <ul>
 *   <li>{@code AUTOMATIC}: silently applied at pricing/cancellation time.</li>
 *   <li>{@code ON_DEMAND}: customer claims in the Membership UI — generates
 *       a fulfilment code (QR / promo).</li>
 *   <li>{@code MANUAL}: CSR applies via admin action (bounded by role).</li>
 * </ul>
 */
@Entity
@Table(name = "loyalty_perk_catalog")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyPerkCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(length = 500)
    private String description;

    /** FINANCIAL / SOFT / INVISIBLE */
    @Column(nullable = false, length = 20)
    private String category;

    /** AUTOMATIC / MANUAL / ON_DEMAND */
    @Column(name = "fulfillment_type", nullable = false, length = 20)
    private String fulfillmentType;

    @Column(name = "delivery_handler_key", nullable = false, length = 80)
    private String deliveryHandlerKey;

    @Column(name = "default_point_cost", nullable = false)
    @Builder.Default
    private long defaultPointCost = 0L;

    @Column(name = "cooldown_hours", nullable = false)
    @Builder.Default
    private int cooldownHours = 0;

    /** Free-form handler-specific parameters. */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

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
