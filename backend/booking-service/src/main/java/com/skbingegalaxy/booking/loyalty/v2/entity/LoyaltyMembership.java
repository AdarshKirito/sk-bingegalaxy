package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — membership (one per customer per program).
 *
 * <p>This is the customer-visible snapshot of tier state.  Recalculated
 * on every event that could change it (earn, expire, adjust, status
 * match, annual rollover job).  Never computed on read — reads are hot
 * paths that must stay O(1).
 *
 * <p>The "tier validity window" ({@code tierEffectiveFrom} /
 * {@code tierEffectiveUntil}) is the heart of the anti-devaluation
 * guarantee: a member is never demoted before {@code tierEffectiveUntil},
 * regardless of how much their qualifying credits drop.
 *
 * <p>{@code memberNumber} is a human-readable SK-XXXX-XXXX identifier
 * (Bonvoy / Delta style), unique, printable, shareable.
 */
@Entity
@Table(name = "loyalty_membership")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "member_number", nullable = false, length = 20)
    private String memberNumber;

    @Column(name = "enrolled_at", nullable = false)
    private LocalDateTime enrolledAt;

    /** SILENT_BOOKING / EXPLICIT_SIGNUP / SSO_GOOGLE / ADMIN_IMPORT / STATUS_MATCH / BACKFILL_V2 */
    @Column(name = "enrollment_source", nullable = false, length = 30)
    private String enrollmentSource;

    @Column(name = "current_tier_code", nullable = false, length = 30)
    @Builder.Default
    private String currentTierCode = "BRONZE";

    @Column(name = "tier_effective_from", nullable = false)
    private LocalDateTime tierEffectiveFrom;

    /** NULL = permanent (Bronze, Lifetime Platinum). */
    @Column(name = "tier_effective_until")
    private LocalDateTime tierEffectiveUntil;

    @Column(name = "soft_landing_eligible", nullable = false)
    @Builder.Default
    private boolean softLandingEligible = true;

    @Column(name = "qualifying_credits_window", nullable = false)
    @Builder.Default
    private long qualifyingCreditsWindow = 0L;

    @Column(name = "lifetime_credits", nullable = false)
    @Builder.Default
    private long lifetimeCredits = 0L;

    @Column(name = "lifetime_years_at_current_tier", nullable = false)
    @Builder.Default
    private int lifetimeYearsAtCurrentTier = 0;

    @Column(name = "status_match_source", length = 120)
    private String statusMatchSource;

    @Column(name = "status_match_expires_at")
    private LocalDateTime statusMatchExpiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "deactivation_reason", length = 255)
    private String deactivationReason;

    @Column(name = "marketing_opt_in", nullable = false)
    @Builder.Default
    private boolean marketingOptIn = false;

    @Column(name = "privacy_flags_json", columnDefinition = "TEXT")
    private String privacyFlagsJson;

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
