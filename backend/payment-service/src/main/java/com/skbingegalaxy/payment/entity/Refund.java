package com.skbingegalaxy.payment.entity;

import com.skbingegalaxy.common.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds", indexes = {
    @Index(name = "idx_refund_payment_id", columnList = "payment_id"),
    @Index(name = "idx_refund_gateway_refund_id", columnList = "gatewayRefundId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    private String reason;

    private String gatewayRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    /**
     * Per-attempt refund lifecycle. See {@link RefundStatus}. Defaults to
     * {@code SUCCEEDED} for backward-compat with the existing synchronous
     * gateway path; new code should set the appropriate value at each
     * transition (INITIATED → PROCESSING → SUCCEEDED/FAILED).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 32)
    @Builder.Default
    private RefundStatus refundStatus = RefundStatus.SUCCEEDED;

    /**
     * If this row is a retry of an earlier failed refund, points to that
     * (now {@link RefundStatus#SUPERSEDED}) row. Null for first-attempt rows.
     */
    @Column(name = "retry_of_id")
    private Long retryOfId;

    /** How many retries have been issued against the original refund attempt. */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    private String gatewayResponse;

    private String failureReason;

    private String initiatedBy;

    private LocalDateTime refundedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
