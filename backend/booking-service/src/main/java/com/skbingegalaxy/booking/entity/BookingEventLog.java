package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Immutable event log for booking state changes.
 * Provides a complete audit trail — every action on a booking is recorded
 * as an append-only event. Supports replay, compliance, and debugging.
 */
@Entity
@Table(name = "booking_event_log", indexes = {
    @Index(name = "idx_bel_booking_ref", columnList = "bookingRef"),
    @Index(name = "idx_bel_event_type", columnList = "eventType"),
    @Index(name = "idx_bel_created_at", columnList = "createdAt")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String bookingRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingEventType eventType;

    /** Previous booking status before this event (null for CREATED) */
    @Column(length = 20)
    private String previousStatus;

    /** New booking status after this event */
    @Column(nullable = false, length = 20)
    private String newStatus;

    /** ID of the user who triggered this event (customer or admin) */
    private Long triggeredBy;

    /** Role of the user who triggered: CUSTOMER, ADMIN, SUPER_ADMIN, SYSTEM */
    @Column(length = 20)
    private String triggeredByRole;

    /** Human-readable description of what changed */
    @Column(length = 2000)
    private String description;

    /** JSON snapshot of key booking fields at the time of this event */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String snapshot;

    /** Schema version of this event (for forward-compatible deserialization) */
    @Builder.Default
    @Column(nullable = false)
    private int eventVersion = 1;

    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
