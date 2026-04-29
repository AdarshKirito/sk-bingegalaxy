package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A time-bounded freeze applied to a single (customerId, bingeId) pair that
 * blocks the customer from creating new bookings at the binge until it
 * expires or is lifted by an admin / super-admin.
 *
 * Created automatically by {@code CustomerFreezeService} when a customer
 * exceeds the binge's pending-cancellation or payment-timeout threshold,
 * or manually by an admin.
 */
@Entity
@Table(name = "customer_binge_freezes", indexes = {
    @Index(name = "idx_freeze_customer_binge_status", columnList = "customerId,bingeId,status"),
    @Index(name = "idx_freeze_binge_status", columnList = "bingeId,status,freezeUntil")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBingeFreeze {

    public enum Status { ACTIVE, LIFTED, EXPIRED }

    public enum TriggerType {
        /** Threshold of customer-initiated cancellations of pending bookings. */
        CUSTOMER_CANCELLATIONS,
        /** Threshold of pending bookings auto-cancelled after payment timeout. */
        PAYMENT_TIMEOUTS,
        /** Manually applied by an admin / super-admin. */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false)
    private LocalDateTime freezeUntil;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TriggerType triggerType;

    private Long triggeredByUserId;

    private Long liftedByUserId;
    private LocalDateTime liftedAt;
    @Column(columnDefinition = "TEXT")
    private String liftedReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.ACTIVE;
    }
}
