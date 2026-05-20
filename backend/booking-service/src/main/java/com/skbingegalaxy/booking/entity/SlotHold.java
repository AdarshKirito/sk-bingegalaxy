package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Temporary slot hold ("reservation") created when a customer enters the
 * checkout step but before payment is captured. Holds give the customer a
 * deterministic countdown window (e.g. 7 minutes) during which the slot is
 * guaranteed against concurrent bookings, after which the hold auto-expires
 * and the slot returns to the pool.
 *
 * <p>Lifecycle:
 * <pre>
 *   ACTIVE ──(payment ok / booking created)──▶ CONVERTED
 *      │
 *      ├──(customer cancels / clicks back / payment fails)──▶ RELEASED
 *      │
 *      └──(expiresAt &lt; now, scheduler)──────▶ EXPIRED
 * </pre>
 */
@Entity
@Table(name = "slot_holds", indexes = {
    @Index(name = "idx_slot_holds_token",        columnList = "hold_token", unique = true),
    @Index(name = "idx_slot_holds_status_expiry", columnList = "status, expires_at"),
    @Index(name = "idx_slot_holds_binge_date",   columnList = "binge_id, booking_date"),
    @Index(name = "idx_slot_holds_customer",     columnList = "customer_id, status")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SlotHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque token returned to the client; used to consume / release the hold. */
    @Column(name = "hold_token", nullable = false, length = 64, unique = true)
    private String holdToken;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_type_id", nullable = false)
    private EventType eventType;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "number_of_guests", nullable = false)
    @Builder.Default
    private int numberOfGuests = 1;

    /** Optional pre-selected room. Counted against room capacity while ACTIVE. */
    @Column(name = "venue_room_id")
    private Long venueRoomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SlotHoldStatus status = SlotHoldStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 80)
    private String releaseReason;

    /** Set when the hold is consumed by a successful booking. */
    @Column(name = "converted_booking_ref", length = 32)
    private String convertedBookingRef;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Optimistic lock — protects against double-consume + double-release races. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    public enum SlotHoldStatus {
        ACTIVE,     // Hold is live and reserves the slot
        CONVERTED,  // Successfully turned into a booking
        RELEASED,   // Manually released (back button / payment failure / abandon)
        EXPIRED     // TTL elapsed without conversion
    }

    public boolean isLive(LocalDateTime now) {
        return status == SlotHoldStatus.ACTIVE && expiresAt != null && expiresAt.isAfter(now);
    }
}
