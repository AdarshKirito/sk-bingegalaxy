package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — platform-level program record.
 *
 * <p>Today we seed exactly one program ({@code SK_MEMBERSHIP}); the schema
 * supports multiple programs (e.g. future white-label sub-programs per
 * tenant) via the {@code tenant_id} column + {@code code} uniqueness.
 *
 * <p>Edits to global behavior toggles (silent enrollment, points expiry
 * window, devaluation-notice window) are made live through the super-admin
 * "Program Settings" surface without a redeploy.
 */
@Entity
@Table(name = "loyalty_program")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "silent_enrollment_enabled", nullable = false)
    @Builder.Default
    private boolean silentEnrollmentEnabled = true;

    @Column(name = "guest_shadow_enabled", nullable = false)
    @Builder.Default
    private boolean guestShadowEnabled = true;

    @Column(name = "retroactive_credit_days", nullable = false)
    @Builder.Default
    private int retroactiveCreditDays = 30;

    /** Default 540 days (18 months) — industry median, more generous than legacy 365. */
    @Column(name = "points_expiry_days", nullable = false)
    @Builder.Default
    private int pointsExpiryDays = 540;

    /** Minimum advance notice before a threshold/rate change can take effect. Anti-devaluation guardrail. */
    @Column(name = "devaluation_notice_days", nullable = false)
    @Builder.Default
    private int devaluationNoticeDays = 90;

    @Column(name = "status_match_enabled", nullable = false)
    @Builder.Default
    private boolean statusMatchEnabled = true;

    @Column(name = "status_challenge_days", nullable = false)
    @Builder.Default
    private int statusChallengeDays = 90;

    @Column(name = "welcome_bonus_points", nullable = false)
    @Builder.Default
    private long welcomeBonusPoints = 500L;

    @Column(name = "birthday_bonus_points", nullable = false)
    @Builder.Default
    private long birthdayBonusPoints = 250L;

    /** Wallet clamp. False = wallet never goes below zero (safer default). */
    @Column(name = "allow_negative_balance", nullable = false)
    @Builder.Default
    private boolean allowNegativeBalance = false;

    /** Point-gifting between members. Disabled for phase 1 (fraud surface). */
    @Column(name = "gifting_enabled", nullable = false)
    @Builder.Default
    private boolean giftingEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
