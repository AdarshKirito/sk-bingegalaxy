package com.skbingegalaxy.booking.permission;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** Immutable audit row for every permission change (who changed whose access). */
@Entity
@Table(name = "binge_permission_audit", indexes = {
    @Index(name = "idx_bpa_binge", columnList = "binge_id, created_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BingePermissionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "module_key", nullable = false, length = 40)
    private String moduleKey;

    @Column(name = "action_key", nullable = false, length = 20)
    @Builder.Default
    private String actionKey = "ALL";

    @Column(name = "old_enabled")
    private Boolean oldEnabled;

    @Column(name = "new_enabled", nullable = false)
    private boolean newEnabled;

    @Column(name = "old_locked")
    private Boolean oldLocked;

    @Column(name = "new_locked", nullable = false)
    private boolean newLocked;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_by_role", nullable = false, length = 32)
    private String changedByRole;

    @Column(length = 500)
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
