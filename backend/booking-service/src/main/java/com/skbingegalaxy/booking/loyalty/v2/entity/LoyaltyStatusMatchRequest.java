package com.skbingegalaxy.booking.loyalty.v2.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Loyalty v2 — status match request.
 *
 * <p>Customer uploads proof of status in a competitor program; admin
 * reviews (or config auto-approves for trusted sources).  On APPROVED,
 * the membership gets a temporary tier grant via a CHALLENGE period —
 * during the challenge the member enjoys the matched tier; they must
 * hit the challenge earning target or they soft-land back.
 *
 * <p>Lifecycle: PENDING → APPROVED / REJECTED → CHALLENGE_ACTIVE →
 * CHALLENGE_EXPIRED (if target not met) or stays at the matched tier.
 */
@Entity
@Table(name = "loyalty_status_match_request")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoyaltyStatusMatchRequest {

    public enum Status { PENDING, APPROVED, REJECTED, CHALLENGE_ACTIVE, CHALLENGE_EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "competitor_program_name", nullable = false, length = 120)
    private String competitorProgramName;

    @Column(name = "competitor_tier_name", nullable = false, length = 60)
    private String competitorTierName;

    @Column(name = "proof_url", length = 500)
    private String proofUrl;

    @Column(name = "proof_payload_json", columnDefinition = "TEXT")
    private String proofPayloadJson;

    @Column(name = "requested_tier_code", nullable = false, length = 30)
    private String requestedTierCode;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "reviewed_by_admin_id")
    private Long reviewedByAdminId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", length = 500)
    private String reviewNotes;

    @Column(name = "challenge_expires_at")
    private LocalDateTime challengeExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
