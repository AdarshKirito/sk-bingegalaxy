package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tracks every Razorpay chargeback/dispute lifecycle event against a payment.
 *
 * A dispute is opened when a customer files a chargeback with their bank.
 * Razorpay holds the disputed amount until the dispute is resolved (won/lost).
 * We must NOT cancel the booking or refund automatically — wait for the gateway
 * to signal the outcome. The ops team is alerted via Slack/PagerDuty immediately
 * so they can gather evidence and respond within the gateway's response window
 * (typically 48-72h).
 *
 * Lifecycle: OPEN → UNDER_REVIEW → WON | LOST | ACCEPTED
 */
@Entity
@Table(
    name = "payment_disputes",
    indexes = {
        @Index(name = "idx_dispute_payment_id",   columnList = "payment_id"),
        @Index(name = "idx_dispute_binge_status", columnList = "binge_id, status"),
        @Index(name = "idx_dispute_created_at",   columnList = "created_at"),
        @Index(name = "idx_dispute_respond_by",   columnList = "respond_by")
    },
    // Name matches the CONSTRAINT name in V12__payment_disputes.sql so ddl-auto=create
    // and Flyway-managed schemas use identical names and won't conflict.
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_dispute_gateway_id", columnNames = "gateway_dispute_id")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "gateway_dispute_id", nullable = false, length = 120)
    private String gatewayDisputeId;

    @Column(name = "binge_id")
    private Long bingeId;

    @Column(name = "booking_ref", nullable = false, length = 60)
    private String bookingRef;

    /**
     * Amount under dispute. May be less than payment.amount for partial disputes.
     */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    @Builder.Default
    private String currency = "INR";

    /**
     * OPEN, UNDER_REVIEW, WON, LOST, ACCEPTED.
     * Matches Razorpay's dispute status vocabulary.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Razorpay dispute reason code (e.g. "not_provided", "goods_not_received"). */
    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** Human-readable reason from the gateway. */
    @Column(name = "reason_description", length = 500)
    private String reasonDescription;

    /** Deadline by which the merchant must respond to Razorpay. ISO-8601 instant. */
    @Column(name = "respond_by")
    private LocalDateTime respondBy;

    /** ISO-8601 timestamp when Razorpay opened the dispute. */
    @Column(name = "gateway_created_at")
    private LocalDateTime gatewayCreatedAt;

    /** Full raw payload from the Razorpay dispute webhook, stored for evidence. */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /** Internal notes added by ops when gathering evidence. */
    @Column(name = "ops_notes", columnDefinition = "TEXT")
    private String opsNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
