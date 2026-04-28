package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * In-app notification surfaced to ADMIN / SUPER_ADMIN users.
 *
 * <p>Backs the bell-icon inbox on the admin entrance dashboard. Used for
 * approval-workflow events (new binge request, approval, rejection,
 * grace-period warning, auto-deactivation) and any future operational alerts.
 */
@Entity
@Table(name = "admin_notifications")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AdminNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User id of the admin/super-admin who should see this notification.
     * {@code null} indicates a role-wide broadcast (visible to anyone with the
     * matching {@link #recipientRole}).
     */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    /** Snapshot of the recipient's role at delivery time (ADMIN / SUPER_ADMIN). */
    @Column(name = "recipient_role", nullable = false, length = 32)
    private String recipientRole;

    /** Machine-readable category, e.g. {@code BINGE_GRACE_WARNING}. */
    @Column(nullable = false, length = 64)
    private String type;

    /** INFO | WARNING | CRITICAL — drives badge colour in the UI. */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String severity = "INFO";

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    /** Optional binge this notification refers to (for deep-linking). */
    @Column(name = "related_binge_id")
    private Long relatedBingeId;

    /** Optional path the UI should navigate to when the user clicks. */
    @Column(name = "action_url", length = 500)
    private String actionUrl;

    /** Null while unread; set to delivery time when the user marks it read. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
