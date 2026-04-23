package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Transactional outbox: Kafka events are written to this table inside
 * the same DB transaction as the domain change. A separate poller
 * picks them up and publishes to Kafka, then marks them as sent.
 */
@Entity
@Table(name = "outbox_event", indexes = {
    @Index(name = "idx_outbox_pending", columnList = "sent, failedPermanent, createdAt")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 30)
    private String aggregateKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Builder.Default
    @Column(nullable = false)
    private boolean sent = false;

    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    /** Number of publish attempts. Incremented on each failure. */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    /** Timestamp of the most recent attempt (success or failure). */
    private LocalDateTime lastAttemptAt;

    /** Truncated error message from the last failed attempt. */
    @Column(length = 1000)
    private String lastError;

    /** True when attempts exceed the configured maximum — event is skipped by the publisher. */
    @Builder.Default
    @Column(nullable = false)
    private boolean failedPermanent = false;
}
