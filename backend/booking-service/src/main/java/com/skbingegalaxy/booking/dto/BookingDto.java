package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingDto {
    private Long id;
    private String bookingRef;
    private Long bingeId;
    /**
     * IANA timezone of the venue this booking belongs to (e.g. "Asia/Kolkata").
     * {@code bookingDate}/{@code startTime} are venue-local wall-clock values — the UI
     * uses this to label them with the correct zone so a customer booking from another
     * timezone is never confused about which clock the time refers to.
     */
    private String venueTimezone;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    /** E.164 dial prefix (e.g. "+91"). */
    private String customerPhoneCountryCode;
    private EventTypeDto eventType;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationHours;
    private Integer durationMinutes;
    private List<BookingAddOnDto> addOns;
    private String specialNotes;
    private String adminNotes;
    private BigDecimal baseAmount;
    private BigDecimal addOnAmount;
    private BigDecimal guestAmount;
    private BigDecimal totalAmount;
    private BigDecimal collectedAmount;
    private BigDecimal balanceDue;      // totalAmount − collectedAmount
    private int numberOfGuests;
    private BookingStatus status;
    private PaymentStatus paymentStatus;
    private String paymentMethod;
    private boolean checkedIn;
    /** True when the customer checked in after the scheduled startTime. Derived state surfaced to admin UI. */
    private boolean lateArrival;
    private LocalDateTime actualCheckInTime;
    private LocalDateTime actualCheckoutTime;
    private Integer actualUsedMinutes;
    private String earlyCheckoutNote;
    private Boolean canCustomerCancel;
    private String customerCancelMessage;
    private Integer cancellationRefundPercentage;
    /**
     * UTC instant when this unpaid PENDING booking will be auto-released by the payment
     * timeout saga. Null for paid/decided bookings. Lets clients show a countdown so
     * the customer knows the reservation exists AND when it expires.
     */
    private LocalDateTime paymentExpiresAt;
    private String pricingSource;
    private String rateCodeName;
    private int rescheduleCount;
    private String originalBookingRef;
    private boolean transferred;
    private String originalCustomerName;
    private String recurringGroupId;

    // ── V85: provenance ──────────────────────────────────────────────────
    /**
     * How this reservation was created — DIRECT, ADMIN or CHANNEL.
     *
     * <p>Exposed because without it a venue cannot tell a channel reservation from
     * one a customer made themselves: reconciliation, commission checks and support
     * triage ("where did this booking come from?") all become guesswork the moment
     * ingestion is live.
     */
    private String origin;

    /** Originating channel slug when {@link #origin} is CHANNEL; null otherwise. */
    private String externalSource;

    /** The channel's own booking reference — what support quotes back to the provider. */
    private String externalRef;
    /** Whether the customer is allowed to reschedule this booking. */
    private Boolean canCustomerReschedule;
    /** Whether the customer is allowed to transfer this booking. */
    private Boolean canCustomerTransfer;
    // ── Venue Room ───
    private Long venueRoomId;
    private String venueRoomName;
    // ── Loyalty Points ───
    private long loyaltyPointsEarned;
    private long loyaltyPointsRedeemed;
    private BigDecimal loyaltyDiscountAmount;
    // ── Surge Pricing ───
    private BigDecimal surgeMultiplier;
    private String surgeLabel;
    // ── Support console ───
    /** NONE / L1 / L2 / L3 — support escalation state shown in the console. */
    private String escalationLevel;
    private String escalationReason;
    private BigDecimal goodwillCredit;
    private String goodwillReason;
    // ── Tax ───
    private BigDecimal subtotalAmount;  // pre-tax subtotal
    private BigDecimal taxAmount;       // exclusive tax charged on top of subtotal
    /**
     * Per-rule tax breakdown persisted at pricing time (JSON array of
     * TaxComputationResult.TaxLine): name, GST/VAT/occupancy type, jurisdiction,
     * rate or flat×units, amount. Lets the admin reservation panel, customer
     * confirmation and printed receipt itemise taxes without recomputing.
     */
    private String taxBreakdownJson;
    // Multi-currency payment: currency the booking is to be paid in (null/"INR" = domestic)
    // and the locked FX rate (foreign units per 1 INR). Lets the payment screen present + charge
    // the locked foreign amount = totalAmount * fxRate.
    private String paymentCurrencyCode;
    private BigDecimal fxRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
