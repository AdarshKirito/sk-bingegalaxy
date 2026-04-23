package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — points wallet (1:1 with membership, split for concurrency).
 *
 * <p>Splitting the wallet off the membership means high-frequency
 * earn/redeem paths lock a small row, not the large customer profile.
 * Engines take a pessimistic lock on the wallet row for the duration of
 * a mutation transaction to serialize concurrent earns and redeems
 * against the same member.
 *
 * <p>The {@code ck_balance_non_negative} DB CHECK is the last-line
 * defense: if an engine ever tries to overdraw, the DB refuses.
 *
 * <p>Lifetime counters (earned / redeemed / expired / adjusted) are
 * running totals maintained at every ledger append so the customer
 * summary UI stays O(1).
 */
@Entity
@Table(name = "loyalty_points_wallet")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyPointsWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "current_balance", nullable = false)
    @Builder.Default
    private long currentBalance = 0L;

    @Column(name = "lifetime_earned", nullable = false)
    @Builder.Default
    private long lifetimeEarned = 0L;

    @Column(name = "lifetime_redeemed", nullable = false)
    @Builder.Default
    private long lifetimeRedeemed = 0L;

    @Column(name = "lifetime_expired", nullable = false)
    @Builder.Default
    private long lifetimeExpired = 0L;

    @Column(name = "lifetime_adjusted", nullable = false)
    @Builder.Default
    private long lifetimeAdjusted = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
