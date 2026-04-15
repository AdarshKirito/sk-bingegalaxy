package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "waitlist_entries", indexes = {
    @Index(name = "idx_waitlist_binge_date", columnList = "bingeId, preferredDate"),
    @Index(name = "idx_waitlist_customer", columnList = "customerId"),
    @Index(name = "idx_waitlist_status", columnList = "status")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 150)
    private String customerName;

    @Column(nullable = false, length = 150)
    private String customerEmail;

    @Column(length = 15)
    private String customerPhone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_type_id", nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private LocalDate preferredDate;

    @Column(nullable = false)
    private LocalTime preferredStartTime;

    @Column(nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    @Builder.Default
    private int numberOfGuests = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WaitlistStatus status = WaitlistStatus.WAITING;

    /** Position in queue (lower = earlier). Auto-assigned on insert. */
    @Column(nullable = false)
    private int position;

    /** When the offer expires (customer must book within this window). */
    private LocalDateTime offerExpiresAt;

    /** When the customer was notified of an opening. */
    private LocalDateTime notifiedAt;

    /** Booking ref if this waitlist entry was converted to a booking. */
    @Column(length = 20)
    private String convertedBookingRef;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum WaitlistStatus {
        WAITING,    // In queue
        OFFERED,    // Slot opened, customer notified
        BOOKED,     // Customer converted to booking
        EXPIRED,    // Offer expired without booking
        CANCELLED   // Customer left the waitlist
    }
}
