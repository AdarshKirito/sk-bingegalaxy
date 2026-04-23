package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — guest shadow account (pre-signup credit accrual).
 *
 * <p>Booking.com-style: a guest who hasn't signed up yet still gets
 * credits accrued against a hashed identifier (email / phone / device).
 * On signup within the retroactive window, the shadow balance migrates
 * into the real membership.  Beyond the window, the shadow expires.
 *
 * <p>PII is HASHED (SHA-256).  We never store the cleartext email/
 * phone/fingerprint — the hash is sufficient for matching at signup.
 */
@Entity
@Table(name = "loyalty_guest_shadow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyGuestShadow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Column(name = "device_fingerprint_hash", length = 64)
    private String deviceFingerprintHash;

    @Column(name = "pending_points", nullable = false)
    @Builder.Default
    private long pendingPoints = 0L;

    @Column(name = "pending_qualifying_credits", nullable = false)
    @Builder.Default
    private long pendingQualifyingCredits = 0L;

    @Column(name = "last_booking_ref", length = 20)
    private String lastBookingRef;

    @CreationTimestamp
    @Column(name = "first_seen_at", updatable = false)
    private LocalDateTime firstSeenAt;

    @UpdateTimestamp
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "merged_membership_id")
    private Long mergedMembershipId;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
