package com.skbingegalaxy.booking.entity;

import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_booking_ref", columnList = "bookingRef"),
    @Index(name = "idx_booking_customer", columnList = "customerId"),
    @Index(name = "idx_booking_date", columnList = "bookingDate")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 20)
    private String bookingRef;

    private Long bingeId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 150)
    private String customerName;

    @Column(nullable = false, length = 150)
    private String customerEmail;

    @Column(nullable = false, length = 15)
    private String customerPhone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_type_id", nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private LocalDate bookingDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private int durationHours;

    /** Canonical duration in minutes (30-min granularity). Falls back to durationHours*60 when null. */
    private Integer durationMinutes;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BookingAddOn> addOns = new ArrayList<>();

    @Column(length = 1000)
    private String specialNotes;

    @Column(length = 1000)
    private String adminNotes;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal addOnAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal guestAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // Sum of all SUCCESS payment amounts minus refunds — updated via Kafka events
    @Column(nullable = true, precision = 10, scale = 2)
    private BigDecimal collectedAmount;

    @Column(nullable = false)
    @Builder.Default
    private int numberOfGuests = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(length = 30)
    private String paymentMethod;

    @Column(nullable = false)
    @Builder.Default
    private boolean checkedIn = false;

    // ── Early checkout tracking ──────────────────────────────
    private LocalDateTime actualCheckoutTime;

    private Integer actualUsedMinutes;

    @Column(length = 500)
    private String earlyCheckoutNote;

    // ── Pricing snapshot ─────────────────────────────────────
    @Column(length = 30)
    private String pricingSource;     // DEFAULT, RATE_CODE, CUSTOMER, ADMIN_OVERRIDE

    @Column(length = 100)
    private String rateCodeName;      // snapshot of rate code name at booking time

    // ── Reschedule tracking ──────────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private int rescheduleCount = 0;

    /** Reference to the original booking if this is a rescheduled slot. */
    @Column(length = 20)
    private String originalBookingRef;

    // ── Transfer tracking ────────────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private boolean transferred = false;

    /** Customer ID of the original booker before transfer. */
    private Long originalCustomerId;

    /** Name of the original booker before transfer. */
    @Column(length = 150)
    private String originalCustomerName;

    // ── Recurring booking group ──────────────────────────────
    /** Shared group identifier linking all bookings in one recurring series. */
    @Column(length = 40)
    private String recurringGroupId;

    // ── Venue room assignment ────────────────────────────────
    /** Selected venue room ID (null = no room preference). */
    private Long venueRoomId;

    /** Snapshot of the room name at booking time. */
    @Column(length = 100)
    private String venueRoomName;

    // ── Loyalty points ───────────────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private long loyaltyPointsEarned = 0;

    @Column(nullable = false)
    @Builder.Default
    private long loyaltyPointsRedeemed = 0;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal loyaltyDiscountAmount;

    // ── Surge pricing snapshot ───────────────────────────────
    @Column(precision = 5, scale = 2)
    private java.math.BigDecimal surgeMultiplier;

    @Column(length = 100)
    private String surgeLabel;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
