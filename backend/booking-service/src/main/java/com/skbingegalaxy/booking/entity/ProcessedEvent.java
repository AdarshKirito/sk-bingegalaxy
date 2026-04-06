package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks Kafka event IDs that have already been processed.
 * Used by consumers to achieve at-least-once → effectively-once semantics.
 */
@Entity
@Table(name = "processed_event", indexes = {
    @Index(name = "idx_pe_event_key", columnList = "eventKey", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Composite key: topic + bookingRef + event-identifying fields */
    @Column(nullable = false, unique = true, length = 200)
    private String eventKey;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime processedAt;
}
