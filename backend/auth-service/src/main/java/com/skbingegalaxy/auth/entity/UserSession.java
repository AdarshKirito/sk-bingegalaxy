package com.skbingegalaxy.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks an active login (refresh-token grant) so a super admin or the user themselves
 * can see where the account is signed in and force-revoke individual devices.
 *
 * <p>The row is identified by the refresh-token {@code jti}; when the token is rotated
 * (normal refresh) the new {@code jti} replaces the old one via {@link #touch}. When the
 * user logs out, or the session is revoked by a super admin, {@link #revokedAt} is set
 * and the corresponding JTI is also placed in {@code revoked_token} so the signature
 * check on /auth/refresh rejects any replay.</p>
 */
@Entity
@Table(name = "user_session")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "refresh_jti", nullable = false, length = 64, unique = true)
    private String refreshJti;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "device_label", length = 255)
    private String deviceLabel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    public void touch(String newJti, LocalDateTime newExpiresAt) {
        this.refreshJti = newJti;
        this.expiresAt = newExpiresAt;
        this.lastSeenAt = LocalDateTime.now();
    }

    public void revoke(Long byUserId, String reason) {
        this.revokedAt = LocalDateTime.now();
        this.revokedBy = byUserId;
        this.revokeReason = reason;
    }
}
