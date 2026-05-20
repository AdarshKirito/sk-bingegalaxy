package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maker-checker (4-eyes) approval request for risky admin actions.
 *
 * <p>Workflow:
 * <pre>
 *  PENDING ──approve──▶ APPROVED ──execute──▶ EXECUTED
 *      │                    │
 *      ├──reject──▶ REJECTED
 *      ├──cancel(by-requester)──▶ CANCELLED
 *      └──ttl────▶ EXPIRED
 * </pre>
 *
 * <p>The reviewer must be a different admin than the requester (separation of
 * duties). Execution is performed by the reviewer in the same approve-call to
 * make the workflow atomic for the human-on-call — but the row stays as proof.
 */
@Entity
@Table(name = "admin_approval_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminApprovalRequest {

    public enum Status { PENDING, APPROVED, REJECTED, EXECUTED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Domain-level action being requested, e.g. {@code REFUND_RETRY}, {@code MANUAL_PAYMENT}. */
    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "resource_type", nullable = false, length = 60)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 120)
    private String resourceId;

    /** JSON snapshot of the action arguments — replayed on approval. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "binge_id")
    private Long bingeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "requested_by", nullable = false, length = 160)
    private String requestedBy;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @CreationTimestamp
    @Column(name = "requested_at", updatable = false, nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "request_reason", length = 1000)
    private String requestReason;

    @Column(name = "reviewed_by", length = 160)
    private String reviewedBy;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** Free-text note from the executor — usually a transaction id or short status. */
    @Column(name = "executed_result", length = 2000)
    private String executedResult;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
