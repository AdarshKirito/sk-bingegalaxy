package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Persists the current state of a booking saga so that:
 * - we know exactly where each booking is in the workflow
 * - we can resume/compensate after a crash
 * - admins can query saga status for troubleshooting
 */
@Entity
@Table(name = "saga_state", indexes = {
    @Index(name = "idx_saga_booking_ref", columnList = "bookingRef", unique = true),
    @Index(name = "idx_saga_status", columnList = "sagaStatus")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String bookingRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private SagaStatus sagaStatus = SagaStatus.STARTED;

    /** Last step that completed successfully */
    @Column(length = 50)
    private String lastCompletedStep;

    /** If saga failed, what went wrong */
    @Column(length = 500)
    private String failureReason;

    /** Number of compensation attempts made */
    @Builder.Default
    private int compensationAttempts = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime startedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    public enum SagaStatus {
        STARTED,
        AWAITING_PAYMENT,
        PAYMENT_RECEIVED,
        CONFIRMED,
        COMPENSATING,
        COMPENSATED,
        FAILED,
        COMPLETED
    }
}
