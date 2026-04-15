package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Defines a cancellation refund tier for a binge.
 * Example tiers: 48h+ before = 100% refund, 24-48h = 50%, <24h = 0%.
 * hoursBeforeStart is the minimum hours before the booking start time for this tier to apply.
 * The system finds the first tier where timeUntilBooking >= hoursBeforeStart (sorted descending).
 */
@Entity
@Table(name = "cancellation_tiers", indexes = {
    @Index(name = "idx_cancel_tier_binge", columnList = "bingeId")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CancellationTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bingeId;

    /** Minimum hours before booking start for this refund tier to apply. */
    @Column(nullable = false)
    private int hoursBeforeStart;

    /** Refund percentage (0-100). */
    @Column(nullable = false)
    private int refundPercentage;

    /** Human-readable label, e.g. "Full refund", "Half refund", "No refund". */
    @Column(length = 100)
    private String label;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
