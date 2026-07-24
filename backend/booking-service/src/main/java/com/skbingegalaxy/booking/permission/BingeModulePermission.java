package com.skbingegalaxy.booking.permission;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One explicit permission decision for (binge, user, module, action).
 *
 * <p>Deny-list semantics: the ABSENCE of a row means "allowed" — the platform
 * behaves exactly as it did before the permission system existed until a
 * super-admin restricts an option. Effective access:</p>
 *
 * <pre>allowed = !lockedBySuperAdmin && enabled</pre>
 *
 * <p>{@code lockedBySuperAdmin} additionally means no parent below SUPER_ADMIN
 * (the binge admin, a future Venue Manager) may re-enable this module for
 * anyone in the binge — the lock wins over every lower-level grant.</p>
 */
@Entity
@Table(name = "binge_module_permissions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_bmp_scope", columnNames = {"binge_id", "user_id", "module_key", "action_key"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BingeModulePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    /** The user this decision applies to (today: the binge's ADMIN). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Role of the target user at grant time: ADMIN, VENUE_MANAGER, STAFF. */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String role = "ADMIN";

    @Column(name = "module_key", nullable = false, length = 40)
    private String moduleKey;

    /** 'ALL' = whole module; finer actions (VIEW/CREATE/…) reuse this table. */
    @Column(name = "action_key", nullable = false, length = 20)
    @Builder.Default
    private String actionKey = "ALL";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "locked_by_super_admin", nullable = false)
    @Builder.Default
    private boolean lockedBySuperAdmin = false;

    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    @Column(name = "granted_by_role", length = 32)
    private String grantedByRole;

    @Column(length = 500)
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Effective access this row grants its subject. */
    public boolean isAllowed() {
        return !lockedBySuperAdmin && enabled;
    }
}
