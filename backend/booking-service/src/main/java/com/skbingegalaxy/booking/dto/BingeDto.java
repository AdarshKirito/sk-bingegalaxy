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
    private String postalCode;
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
    private boolean customerCancellationEnabled;
    private int customerCancellationCutoffMinutes;
    private Integer maxConcurrentBookings;
    private LocalTime openTime;
    private LocalTime closeTime;
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
