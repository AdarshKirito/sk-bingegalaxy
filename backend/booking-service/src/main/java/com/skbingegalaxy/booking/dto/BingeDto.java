package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeDto {
    private Long id;
    private String name;
    private String address;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String country;
    /** ISO-4217 currency derived from {@link #country}; the binge's sole pricing currency. */
    private String currency;
    private String postalCode;
    /** WGS-84 latitude in decimal degrees; null when the venue is not geocoded. */
    private Double latitude;
    /** WGS-84 longitude in decimal degrees; null when the venue is not geocoded. */
    private Double longitude;
    /**
     * Distance in kilometres from the query point, rounded to one decimal. Only
     * populated by the proximity endpoint ({@code /binges/nearby}); null elsewhere.
     */
    private Double distanceKm;
    /** IANA timezone for this venue, e.g. "Asia/Kolkata", "America/New_York". */
    private String timezone;
    private Long adminId;
    private boolean active;
    private LocalDate operationalDate;
    private String supportEmail;
    private String supportPhone;
    private String supportPhoneCountryCode;
    private String supportWhatsapp;
    private String supportWhatsappCountryCode;
    /** V78: public support phone doubles as the WhatsApp contact. */
    private boolean supportPhoneIsWhatsapp;
    // V78: personal/owner contact — ADMIN-surface only (this DTO is never
    // served to customers; they receive PublicBingeDto).
    private String ownerEmail;
    private String ownerPhone;
    private String ownerPhoneCountryCode;
    private boolean ownerPhoneIsWhatsapp;
    private boolean customerCancellationEnabled;
    private int customerCancellationCutoffMinutes;
    private Integer maxConcurrentBookings;
    private LocalTime openTime;
    private LocalTime closeTime;
    /** V81 venue-wide default prep time before every booking, in minutes. Event types may override. */
    private Integer defaultSetupMinutes;
    /** V81 venue-wide default turnover time after every booking, in minutes. Event types may override. */
    private Integer defaultCleanupMinutes;
    /**
     * V83: when an operator explicitly confirmed the turnover buffers. NULL means never
     * reviewed — the console prompts, and the venue should not be published to a sales
     * channel until it is set. Deliberately choosing 0 counts as reviewed.
     */
    private LocalDateTime turnoverPolicyReviewedAt;
    /** V84 (G5): minimum lead time in minutes before a booking may start. */
    private Integer minNoticeMinutes;
    /** V84 (G5): advance booking horizon in days. NULL = inherit the platform default. */
    private Integer maxAdvanceDays;
    /** Optional per-day operating hours (overrides open/close for the matching day). */
    private java.util.List<BingeDayHours> openingHours;
    private LocalDateTime createdAt;

    /** V56: when true, the customer must pick a venue room during booking. */
    private boolean roomSelectionRequired;

    // ── Cancellation policy + freeze settings (binge-level) ──
    private boolean freezePolicyEnabled;
    private int freezeDurationMinutes;
    private int maxPendingCancelsBeforeFreeze;
    private int maxPendingPaymentTimeoutsBeforeFreeze;
    private boolean refundOnSuccessfulPaymentCancel;
    private boolean refundOnPendingPaymentCancel;
    /** Master tax switch for this venue (super-admin controlled). */
    private boolean taxesEnabled;

    /** Approval status: PENDING_APPROVAL, APPROVED, or REJECTED. */
    private String status;
    private Long approvalDecidedBy;
    private LocalDateTime approvalDecidedAt;
    private String approvalRejectionReason;

    /** Grace-period bookkeeping (visible to admin UI for banners/countdowns). */
    private LocalDateTime firstEventCreatedAt;
    private LocalDateTime graceWarningSentAt;
    private LocalDateTime autoDeactivatedAt;
}
