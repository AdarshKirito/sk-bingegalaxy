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
    @Index(name = "idx_booking_ref",              columnList = "bookingRef"),
    @Index(name = "idx_booking_customer",          columnList = "customerId"),
    @Index(name = "idx_booking_date",              columnList = "bookingDate"),
    // Composite: supports "WHERE customerId=? AND bookingDate>=? AND status IN (...)"
    @Index(name = "idx_booking_customer_date",     columnList = "customerId, bookingDate"),
    // Composite: supports binge-scoped date-range queries used in availability checks
    @Index(name = "idx_booking_binge_date_status", columnList = "bingeId, bookingDate, status"),
    // Composite: supports admin booking list filtered by binge + status (common admin view)
    @Index(name = "idx_booking_binge_status",      columnList = "bingeId, status")
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

    @Column(nullable = false, length = 20)
    private String customerPhone;

    /** E.164 dial prefix (e.g. "+91"). Captured separately so SMS/WhatsApp can target international numbers. */
    @Column(name = "customer_phone_country_code", length = 8)
    private String customerPhoneCountryCode;

    @ManyToOne(fetch = FetchType.LAZY)
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

    // ── Item 24 — support console fields ─────────────────────────────────
    /**
     * Customer-supplied or admin-supplied cancellation reason. Captured at
     * cancel time so the support timeline shows WHY a booking was cancelled
     * without parsing free-form notes. Null until cancelled.
     */
    @Column(length = 500)
    private String cancellationReason;

    /**
     * Escalation level for support cases. NONE means no active escalation.
     * Stored as a String (not enum) to keep the column open to product
     * tweaks without a migration; controller accepts a fixed enum-like set
     * (NONE, L1, L2, L3).
     */
    @Column(length = 16)
    @Builder.Default
    private String escalationLevel = "NONE";

    /** Free-text reason supplied with the most recent escalation. */
    @Column(length = 500)
    private String escalationReason;

    /** Goodwill credit (in rupees, mirrors totalAmount precision) issued to this booking by support. */
    @Column(precision = 10, scale = 2)
    private BigDecimal goodwillCredit;

    /** Why the goodwill was issued (e.g. "missed reminder"). */
    @Column(length = 500)
    private String goodwillReason;

    /** Admin user id who issued the goodwill (for audit). */
    private Long goodwillIssuedByAdminId;

    private java.time.LocalDateTime goodwillIssuedAt;

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

    /**
     * True when the customer checked in after their {@code startTime} (plus
     * the configured grace window). Set at consume-token time; used by the
     * admin UI to surface a derived "LATE_ARRIVAL" pill without expanding
     * the {@link BookingStatus} enum.
     */
    @Column(name = "late_arrival", nullable = false)
    @Builder.Default
    private boolean lateArrival = false;

    // ── Check-in tracking ────────────────────────────────────
    private LocalDateTime actualCheckInTime;

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

    /**
     * V56: snapshot of the room's price_addition at booking time. The room's
     * pricing is included in the subtotal-for-tax base, mirroring how
     * eventBasePrice/eventHourlyAmount are captured so the booking stays
     * reproducible even after the catalogue changes later.
     */
    @Column(name = "venue_room_price", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal venueRoomPrice = BigDecimal.ZERO;

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

    // ── Tax / currency snapshot (V38 + V39 migrations) ───────
    /** Pre-tax subtotal in base currency (V38). */
    @Column(name = "subtotal_amount", precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    /** Total tax charged in base currency (V38). */
    @Column(name = "tax_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** Per-rule tax breakdown serialised as JSON for invoice rendering (V38). */
    @Column(name = "tax_breakdown_json", columnDefinition = "text")
    private String taxBreakdownJson;

    /** Currency the customer was quoted in at booking time (V38). */
    @Column(name = "currency_code", length = 8)
    @Builder.Default
    private String currencyCode = "INR";

    /** Total in the display currency, after FX conversion (V38). */
    @Column(name = "display_amount", precision = 14, scale = 2)
    private BigDecimal displayAmount;

    /** FX rate captured at booking time so historical invoices stay reproducible (V38). */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal fxRate = BigDecimal.ONE;

    /** Canonical base currency the booking is stored in (V39). */
    @Column(name = "base_currency_code", length = 8)
    private String baseCurrencyCode;

    /** Currency shown to the customer; usually the same as quoted (V39). */
    @Column(name = "display_currency_code", length = 8)
    private String displayCurrencyCode;

    /** Currency the payment was charged in (V39). */
    @Column(name = "payment_currency_code", length = 8)
    private String paymentCurrencyCode;

    /** Currency funds will be settled into (V39). */
    @Column(name = "settlement_currency_code", length = 8)
    private String settlementCurrencyCode;

    /** FK to immutable {@code booking_price_snapshots} row (V39). */
    @Column(name = "price_snapshot_id")
    private Long priceSnapshotId;

    /** FK to {@code billing_addresses} row used for invoice (V39). */
    @Column(name = "billing_address_id")
    private Long billingAddressId;

    /** When the FX quote stops being valid (V39). */
    @Column(name = "fx_locked_until")
    private LocalDateTime fxLockedUntil;

    /** Schema version for the pricing/tax calculation engine (V39). */
    @Column(name = "calculation_version", nullable = false)
    @Builder.Default
    private int calculationVersion = 1;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Who triggered the cancellation: CUSTOMER, ADMIN, or SYSTEM. Populated
     * by {@code BookingService#cancelBooking}; null for non-cancelled rows.
     * Used by the freeze-policy to differentiate customer-initiated cancels
     * from payment-timeout auto-cancels.
     */
    @Column(name = "cancellation_actor", length = 20)
    private String cancellationActor;

    /**
     * Defensive defaulting for the V38 finance-snapshot columns. The DB
     * marks {@code subtotal_amount} as {@code NOT NULL}; if any code path
     * forgets to set it (we already had one such regression), default it
     * to {@code totalAmount} so the persist succeeds with the same value
     * the V38 backfill would have written. {@code taxAmount} also gets a
     * zero default for the rare case the {@code @Builder.Default} was
     * bypassed (e.g. reflective construction).
     *
     * <p>This is belt-and-suspenders: the canonical place to compute the
     * pre-tax subtotal is the booking creation flow; this hook only
     * guards against the column-not-set error class.
     */
    @PrePersist
    void prePersistFinanceDefaults() {
        if (this.subtotalAmount == null) {
            this.subtotalAmount = this.totalAmount;
        }
        if (this.taxAmount == null) {
            this.taxAmount = BigDecimal.ZERO;
        }
        if (this.currencyCode == null) {
            this.currencyCode = "INR";
        }
    }
}
