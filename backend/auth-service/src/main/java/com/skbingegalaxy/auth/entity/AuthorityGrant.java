package com.skbingegalaxy.auth.entity;

import com.skbingegalaxy.common.enums.AuthorityScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * A time-bounded delegation of super-admin privileges, issued by a super-admin to a
 * specific admin for one or more {@link AuthorityScope}s.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Created via {@code POST /auth/authority/grants}. The {@code reason} is required
 *       and surfaced in audit logs.</li>
 *   <li>Becomes <em>effective</em> the moment a refreshed JWT is issued to the grantee.</li>
 *   <li>Auto-expires at {@link #expiresAt}. Maximum 24 hours from creation, default 4 h.</li>
 *   <li>Can be revoked early via {@code DELETE /auth/authority/grants/{id}} — sets
 *       {@link #revokedAt}. The grantee's current sessions are immediately revoked
 *       (auth-service revokes the JWT), forcing re-login or token refresh.</li>
 * </ul>
 *
 * <h3>Security model</h3>
 * <p>The grant only widens access — it never narrows it. A grantee who is already
 * SUPER_ADMIN gains nothing. The grant is enforced in two places:</p>
 * <ol>
 *   <li><b>JWT issue/refresh time</b> — auth-service inspects active grants and stamps
 *       the JWT with {@code role=SUPER_ADMIN} plus {@code delegatedScopes} claims.</li>
 *   <li><b>Gateway request time</b> — gateway compares request path against
 *       {@code delegatedScopes} for every super-admin path; non-listed scopes
 *       receive 403.</li>
 * </ol>
 *
 * <p>Rows are append-only after creation; only {@link #revokedAt}/{@link #revokedBy}/
 * {@link #revokeReason} may be updated, and only once.</p>
 */
@Entity
@Table(
    name = "authority_grants",
    indexes = {
        @Index(name = "idx_authority_grants_grantee", columnList = "grantee_user_id"),
        @Index(name = "idx_authority_grants_active",  columnList = "grantee_user_id,expires_at,revoked_at")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AuthorityGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The admin (or super-admin — no-op) who receives elevated privileges. */
    @Column(name = "grantee_user_id", nullable = false)
    private Long granteeUserId;

    /**
     * Scopes for which the grantee is treated as a super-admin while the grant is
     * active. Stored in a side table to support querying by scope. Always non-empty.
     */
    @ElementCollection(fetch = FetchType.EAGER, targetClass = AuthorityScope.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "authority_grant_scopes",
        joinColumns = @JoinColumn(name = "grant_id", nullable = false),
        foreignKey = @ForeignKey(name = "fk_authority_grant_scopes_grant")
    )
    @Column(name = "scope", nullable = false, length = 32)
    @Builder.Default
    private Set<AuthorityScope> scopes = new HashSet<>();

    /** Super-admin user id who created the grant. Required for audit. */
    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    /** Required free-text business justification. Surfaced in audit log. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    /** Expiry instant. Server clamps to max(now + 24h) at creation time. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when revoked early; null while the grant is still in effect. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Super-admin who revoked the grant (may equal {@link #grantedBy}). */
    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    /**
     * @return true iff the grant is currently in force (not revoked and not expired).
     */
    @Transient
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
