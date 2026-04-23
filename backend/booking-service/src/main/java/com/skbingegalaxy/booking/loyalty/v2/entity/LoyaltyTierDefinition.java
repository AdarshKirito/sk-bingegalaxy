package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — effective-dated tier definition.
 *
 * <p>Super-admin edits NEVER mutate an existing row.  They create a new
 * row with a new {@code effectiveFrom} and close the old row's
 * {@code effectiveTo} — the ledger can then reproduce any historical
 * tier calculation.  This is the anti-devaluation guarantee.
 *
 * <p>{@code validityCalendarYearsAfter = 1} means "tier held through the
 * end of the NEXT calendar year after qualifying" (Bonvoy model).
 * {@code NULL} means permanent (Bronze, Lifetime tiers).
 */
@Entity
@Table(name = "loyalty_tier_definition")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyTierDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "rank_order", nullable = false)
    private int rankOrder;

    @Column(name = "qualification_credits_required", nullable = false)
    @Builder.Default
    private long qualificationCreditsRequired = 0L;

    @Column(name = "qualification_window_days", nullable = false)
    @Builder.Default
    private int qualificationWindowDays = 365;

    @Column(name = "lifetime_credits_required")
    private Long lifetimeCreditsRequired;

    @Column(name = "lifetime_years_held_required")
    private Integer lifetimeYearsHeldRequired;

    /** NULL = permanent. 1 = "through end of next calendar year" (Bonvoy). */
    @Column(name = "validity_calendar_years_after")
    private Integer validityCalendarYearsAfter;

    @Column(name = "soft_landing_tier_code", length = 30)
    private String softLandingTierCode;

    @Column(name = "color_hex", length = 9)
    private String colorHex;

    @Column(name = "icon_key", length = 40)
    private String iconKey;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
