package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — per-binge opt-in registry (the "Marriott brand family" layer).
 *
 * <p>A row exists only after a binge has interacted with the program:
 * opted in, opted out, or been grandfathered by the V22 backfill.
 * Absence means the binge has never been evaluated — new binges default
 * to their admin's choice at creation time.
 *
 * <p>{@code status = ENABLED_LEGACY} + {@code legacyFrozen = true} is the
 * migration bucket: current binges land here so their customer-visible
 * behavior is preserved.  Admins can explicitly re-configure them later.
 */
@Entity
@Table(name = "loyalty_binge_binding")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyBingeBinding {

    /** ENABLED, DISABLED, ENABLED_LEGACY */
    public enum Status { ENABLED, DISABLED, ENABLED_LEGACY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "legacy_frozen", nullable = false)
    @Builder.Default
    private boolean legacyFrozen = false;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    @Column(name = "enrolled_by_admin_id")
    private Long enrolledByAdminId;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "disabled_by_admin_id")
    private Long disabledByAdminId;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

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
