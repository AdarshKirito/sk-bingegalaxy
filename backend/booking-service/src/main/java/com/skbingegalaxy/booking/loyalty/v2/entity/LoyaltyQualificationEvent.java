package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — qualification-credit event (rolling 12-month window).
 *
 * <p>Separate from the points ledger because qualifying credits govern
 * TIER, not wallet balance.  A booking typically produces BOTH a
 * {@code loyalty_points_lot} (redeemable) and a
 * {@code loyalty_qualification_event} (tier) — often at different rates
 * (e.g. QC multiplier on luxury binges).
 *
 * <p>The {@code expiresFromWindowAt} column gives us a cheap range scan
 * for the nightly tier-recalc job: "sum all active QCs for a
 * membership" becomes an indexed sum over a partial index.  Never
 * recompute from the booking table.
 *
 * <p>Event is IMMUTABLE — reversals happen via new rows with negative
 * credits (e.g. cancellation beyond the retroactive window).
 */
@Entity
@Table(name = "loyalty_qualification_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyQualificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "booking_ref", length = 20)
    private String bookingRef;

    /** BOOKING_COMPLETED / SPEND_MILESTONE / STATUS_MATCH_GRANT / BONUS_PROMO */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "qualification_credits", nullable = false)
    private long qualificationCredits;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;

    @Column(name = "expires_from_window_at", nullable = false)
    private LocalDateTime expiresFromWindowAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
