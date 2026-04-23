package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — FIFO points lot.
 *
 * <p>Every EARN creates exactly one lot with {@code remainingPoints ==
 * originalPoints}.  Redemptions and expirations decrement
 * {@code remainingPoints} on the OLDEST eligible lots first (ordered by
 * {@code earnedAt}).  This is what makes partial expirations correct:
 * when a lot's expiry fires, only its {@code remainingPoints} go away —
 * not an arbitrary chunk of the wallet.
 *
 * <p>{@code bingeId} is nullable: program-wide lots (welcome bonus,
 * birthday bonus, status-match grants, admin adjustments) have no binge
 * scope.  Per-binge earns reference their source binge for analytics
 * and for binge-scoped expiry promos.
 */
@Entity
@Table(name = "loyalty_points_lot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyPointsLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "binge_id")
    private Long bingeId;

    /** EARN_BOOKING / BONUS_WELCOME / BONUS_BIRTHDAY / ADMIN_ADJUSTMENT / STATUS_MATCH_GRANT */
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "original_points", nullable = false)
    private long originalPoints;

    @Column(name = "remaining_points", nullable = false)
    private long remainingPoints;

    @Column(name = "earned_at", nullable = false)
    private LocalDateTime earnedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
