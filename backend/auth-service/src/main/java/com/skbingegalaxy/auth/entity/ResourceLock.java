package com.skbingegalaxy.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A super-admin–owned lock on a specific resource. While the lock exists, even a
 * delegated admin (one holding an active {@link AuthorityGrant}) cannot mutate the
 * resource. Only the super-admin who placed the lock — or any user with the native
 * {@code SUPER_ADMIN} role and no overriding grant chain — may release it.
 *
 * <p>Locks are advisory at the auth-service layer (we only own the table) and become
 * binding when consumed by a downstream service that calls
 * {@code GET /auth/authority/locks?type=...&id=...} during its mutation pre-checks,
 * <em>or</em> by the gateway via the {@code X-Resource-Lock} header on responses.</p>
 *
 * <p>A composite uniqueness constraint on (resource_type, resource_id) ensures only
 * one active lock per resource. Releasing a lock physically deletes the row; the
 * release event is preserved in {@link AuthAuditLog} for compliance.</p>
 */
@Entity
@Table(
    name = "resource_locks",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_resource_locks_type_id",
        columnNames = {"resource_type", "resource_id"}
    ),
    indexes = @Index(name = "idx_resource_locks_owner", columnList = "locked_by")
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ResourceLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Logical resource type, e.g. {@code CURRENCY}, {@code NOTIFICATION_TEMPLATE},
     * {@code LOYALTY_TIER}, {@code USER}, {@code SITE_CONTENT}. Free-form so new
     * resource types can be locked without a schema change; consumers compare
     * case-insensitively.
     */
    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    /** Free-form business id of the locked resource (e.g. {@code "USD"}, {@code "42"}). */
    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    /** User id of the super-admin who placed the lock. */
    @Column(name = "locked_by", nullable = false)
    private Long lockedBy;

    /** Display name captured at lock-time so the UI can show owner without a join. */
    @Column(name = "locked_by_name", length = 200)
    private String lockedByName;

    /** Required reason, shown in the lock badge in the UI. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "locked_at", nullable = false, updatable = false)
    private LocalDateTime lockedAt;
}
