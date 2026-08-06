package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One venue's authorisation to one connectivity provider.
 *
 * <p><b>Per-venue, not platform-wide</b> ({@code bingeId} is NOT NULL). The venue is the
 * supplier of record: GetYourGuide explicitly refuses <em>"resellers, aggregators,
 * online travel agencies"</em> as supply partners, and SK Binge is an aggregator of
 * third-party venues. A platform-level connection would contradict the contracting model
 * the whole distribution strategy rests on. SK Binge occupies the reservation-system
 * seat, exactly as Bókun and Ventrata do.
 */
@Entity
@Table(name = "connections")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private Environment environment = Environment.SANDBOX;

    /**
     * A <b>pointer</b> into the secrets manager — never the secret.
     *
     * <p>No API returns it, nothing logs it, and the frontend receives only
     * {@link #credentialHint}. Credential sprawl was flagged as a P0 risk precisely
     * because this repository already had a secrets incident.
     */
    @Column(name = "credential_ref", length = 200)
    private String credentialRef;

    /** Masked tail for display, e.g. {@code ••••1234}. Safe to send to a browser. */
    @Column(name = "credential_hint", length = 40)
    private String credentialHint;

    /**
     * Monitored, because some providers expire credentials on a schedule — Google
     * Actions Center rotates basic-auth every six months. An expired credential should
     * raise a warning before it silently stops the channel.
     */
    @Column(name = "credential_expires_at")
    private LocalDateTime credentialExpiresAt;

    /**
     * SHA-256 of the key SK Binge issued to the RESELLER for this connection (V3).
     *
     * <p>The opposite direction to {@link #credentialRef}, which points at the secret we
     * present <em>to</em> a provider. OCTO is supplier-hosted: the reseller presents a
     * key to us, so this one is only ever verified, never replayed — which is why the
     * digest is stored and the key itself is shown exactly once, at issue.
     */
    @Column(name = "reseller_key_hash", length = 64)
    private String resellerKeyHash;

    /** Masked tail, e.g. {@code ••••a1b2}. Safe to send to a browser. */
    @Column(name = "reseller_key_hint", length = 40)
    private String resellerKeyHint;

    @Column(name = "reseller_key_issued_at")
    private LocalDateTime resellerKeyIssuedAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /**
     * Pause stops <b>all</b> traffic on this connection. Distinct from stop-sell on
     * {@link ConnectionDestination}, which halts new sales while still honouring
     * reservations already taken.
     */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "paused_reason", length = 300)
    private String pausedReason;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum ConnectionStatus {
        PENDING,
        /** Waiting on the provider: certification, pilot approval, or a signed agreement. */
        AWAITING_PROVIDER,
        ACTIVE,
        /** Reachable but failing — surfaced in Health rather than silently retried forever. */
        DEGRADED,
        PAUSED,
        REVOKED
    }

    /** Sandbox and production coexist for the same venue and provider; duplicates of either do not. */
    public enum Environment { SANDBOX, PRODUCTION }
}
