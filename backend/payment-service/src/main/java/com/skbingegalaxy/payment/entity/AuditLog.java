package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only audit trail for money-moving actions.
 *
 * <p>Separate from {@link PaymentStatusHistory} which tracks automated state
 * transitions — this table captures deliberate, human-or-system-initiated
 * actions (refund issued, payment cancelled, cash recorded, manual add-payment)
 * with the actor that performed them. Required for finance reconciliation,
 * dispute defence, and operator forensics.
 */
@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_log_resource", columnList = "resource_type, resource_id"),
    @Index(name = "idx_audit_log_actor",    columnList = "actor"),
    @Index(name = "idx_audit_log_created",  columnList = "created_at"),
    @Index(name = "idx_audit_log_binge",    columnList = "binge_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
