package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * A gated change to a binge that a regular admin cannot apply directly and must
 * instead route through super-admin review — currently only {@code COUNTRY_CHANGE}
 * (which reprices the whole venue because the currency is derived from the country).
 *
 * <p>Full state machine with audit trail:
 * <pre>PENDING → APPROVED | REJECTED | CANCELLED</pre>
 * APPROVED applies the change atomically in the same transaction. Every decision
 * records who decided, when, and an optional note. A super-admin editing the
 * country directly supersedes (cancels) any pending request for the same binge.
 */
@Entity
@Table(name = "binge_change_requests", indexes = {
    @Index(name = "idx_bcr_status", columnList = "status"),
    @Index(name = "idx_bcr_binge", columnList = "binge_id"),
    @Index(name = "idx_bcr_requester", columnList = "requested_by_admin_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeChangeRequest {

    public enum Type {
        COUNTRY_CHANGE,
        /**
         * A regular admin cannot set a venue's timezone directly — it is
         * auto-derived from the address. When the admin believes the derived zone
         * is wrong they raise this request (with a mandatory reason and an optional
         * suggested zone); a super-admin resolves it during review.
         */
        TIMEZONE_CHANGE
    }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 40)
    @Builder.Default
    private Type requestType = Type.COUNTRY_CHANGE;

    /** Value at the time of the request (e.g. current country "IN"). */
    @Column(name = "current_value", length = 100)
    private String currentValue;

    /** Requested new value (e.g. "US"). */
    @Column(name = "requested_value", nullable = false, length = 100)
    private String requestedValue;

    /** Currency the binge would switch to if approved — preview for the reviewer. */
    @Column(name = "requested_currency", length = 3)
    private String requestedCurrency;

    @Column(length = 500)
    private String reason;

    @Column(name = "requested_by_admin_id", nullable = false)
    private Long requestedByAdminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
