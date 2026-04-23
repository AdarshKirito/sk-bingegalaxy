package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — IMMUTABLE ledger entry.
 *
 * <p>Every mutation of {@link LoyaltyPointsWallet#getCurrentBalance()}
 * is mirrored by exactly one row here, including the signed delta and
 * the reason.  Corrections are compensating entries (REVERSE_EARN,
 * REVERSE_REDEEM) — never UPDATEs.  This means:
 *
 * <ul>
 *   <li>The wallet balance is always reproducible by summing the ledger.</li>
 *   <li>Disputes / CS cases can show the customer exactly when and why
 *       their balance changed.</li>
 *   <li>The history is append-only, so backups and audits are trivial.</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} combined with {@code walletId} and
 * {@code entryType} gives a unique constraint — a retried earn operation
 * can safely re-submit and either see "already applied" or succeed,
 * never double-credit.
 */
@Entity
@Table(name = "loyalty_ledger_entry")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** EARN / REDEEM / EXPIRE / ADJUST / REVERSE_EARN / REVERSE_REDEEM / BONUS / STATUS_MATCH_GRANT / TRANSFER_IN / TRANSFER_OUT */
    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;

    /** Signed — negative for REDEEM/EXPIRE/REVERSE_EARN/TRANSFER_OUT, positive otherwise. */
    @Column(name = "points_delta", nullable = false)
    private long pointsDelta;

    @Column(name = "lot_id")
    private Long lotId;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "booking_ref", length = 20)
    private String bookingRef;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @Column(length = 500)
    private String description;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
