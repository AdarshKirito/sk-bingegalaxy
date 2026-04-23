package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — non-financial membership lifecycle event.
 *
 * <p>Drives both the customer-visible activity timeline and the admin
 * audit log.  Financial changes live in {@link LoyaltyLedgerEntry};
 * things like tier promotions, enrollment events, reward claims, and
 * status-match approvals live here.
 *
 * <p>Immutable — {@code from_value_json} / {@code to_value_json} capture
 * the state transition for replay.
 */
@Entity
@Table(name = "loyalty_membership_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyMembershipEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    /** ENROLLED / TIER_UP / TIER_DOWN / SOFT_LANDING / STATUS_MATCH_APPROVED / REWARD_CLAIMED / DEACTIVATED / REACTIVATED / ... */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "from_value_json", columnDefinition = "TEXT")
    private String fromValueJson;

    @Column(name = "to_value_json", columnDefinition = "TEXT")
    private String toValueJson;

    /** SYSTEM / ADMIN / CUSTOMER */
    @Column(name = "triggered_by", nullable = false, length = 20)
    private String triggeredBy;

    @Column(name = "triggered_by_id")
    private Long triggeredById;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
