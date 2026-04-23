package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — reward claim record.
 *
 * <p>Links a membership to either a platform perk ({@code perkId}) or a
 * binge reward item ({@code bingeRewardItemId}); the DB check constraint
 * enforces exactly one source.
 *
 * <p>Lifecycle: RESERVED → FULFILLED (redeemed / consumed) or CANCELLED
 * / EXPIRED.  The expiry background job scans the partial index
 * {@code idx_reward_expiry_job} where {@code status = 'RESERVED'} so it
 * is O(expiring-rewards) not O(all-rewards).
 */
@Entity
@Table(name = "loyalty_reward_claim")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyRewardClaim {

    public enum Status { RESERVED, FULFILLED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "perk_id")
    private Long perkId;

    @Column(name = "binge_reward_item_id")
    private Long bingeRewardItemId;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "booking_ref", length = 20)
    private String bookingRef;

    @Column(name = "points_cost", nullable = false)
    private long pointsCost;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "fulfillment_code", length = 80)
    private String fulfillmentCode;

    @Column(name = "fulfillment_payload_json", columnDefinition = "TEXT")
    private String fulfillmentPayloadJson;

    @CreationTimestamp
    @Column(name = "claimed_at", updatable = false)
    private LocalDateTime claimedAt;

    @Column(name = "fulfilled_at")
    private LocalDateTime fulfilledAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
