package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "binges")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Binge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String address;

    /** Optional structured address (street/city/state/country/postal). */
    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    /** ISO-3166-1 alpha-2 country code, e.g. "IN", "US". */
    @Column(length = 2)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(nullable = false)
    private Long adminId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Per-binge operational date – advances only after a successful audit */
    @Column
    private LocalDate operationalDate;

    @Column(columnDefinition = "TEXT")
    private String customerDashboardConfigJson;

    @Column(columnDefinition = "TEXT")
    private String customerAboutConfigJson;

    @Column(length = 150)
    private String supportEmail;

    @Column(length = 20)
    private String supportPhone;

    /** E.164 dial prefix for {@link #supportPhone}, e.g. "+91". */
    @Column(name = "support_phone_country_code", length = 8)
    private String supportPhoneCountryCode;

    @Column(length = 20)
    private String supportWhatsapp;

    /** E.164 dial prefix for {@link #supportWhatsapp}, e.g. "+91". */
    @Column(name = "support_whatsapp_country_code", length = 8)
    private String supportWhatsappCountryCode;

    @Column(nullable = false)
    @Builder.Default
    private boolean customerCancellationEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private int customerCancellationCutoffMinutes = 180;

    // ── Customer freeze policy (anti-abuse) ──────────────────────────────
    /** Toggle for the entire freeze-on-abuse machinery at this binge. */
    @Column(name = "freeze_policy_enabled", nullable = false)
    @Builder.Default
    private boolean freezePolicyEnabled = true;

    /** How long (minutes) a triggered freeze blocks the customer. */
    @Column(name = "freeze_duration_minutes", nullable = false)
    @Builder.Default
    private int freezeDurationMinutes = 60;

    /** Max customer-initiated cancellations of pending bookings within freezeDurationMinutes before freeze is applied. */
    @Column(name = "max_pending_cancels_before_freeze", nullable = false)
    @Builder.Default
    private int maxPendingCancelsBeforeFreeze = 3;

    /** Max bookings auto-cancelled by payment-timeout within freezeDurationMinutes before freeze is applied. */
    @Column(name = "max_pending_payment_timeouts_before_freeze", nullable = false)
    @Builder.Default
    private int maxPendingPaymentTimeoutsBeforeFreeze = 3;

    // ── Cancellation refund applicability ────────────────────────────────
    /** When TRUE, the configured tiered refund applies to bookings cancelled after a SUCCESSFUL payment. */
    @Column(name = "refund_on_successful_payment_cancel", nullable = false)
    @Builder.Default
    private boolean refundOnSuccessfulPaymentCancel = true;

    /** When TRUE, the configured tiered refund applies to bookings cancelled while still PENDING payment. */
    @Column(name = "refund_on_pending_payment_cancel", nullable = false)
    @Builder.Default
    private boolean refundOnPendingPaymentCancel = false;

    /** Maximum concurrent bookings per time slot. Null = unlimited. */
    @Column
    private Integer maxConcurrentBookings;
/**
     * Per-binge opening time (local, theater timezone). When null the global
     * {@code app.theater.opening-hour} fallback applies. Booking-service rejects
     * any booking whose {@code startTime} falls before this value.
     */
    @Column(name = "open_time")
    private LocalTime openTime;

    /**
     * Per-binge closing time (local, theater timezone). When null the global
     * {@code app.theater.closing-hour} fallback applies. Booking-service rejects
     * any booking whose {@code startTime + duration} extends past this value.
     * Must be strictly greater than {@link #openTime}.
     */
    @Column(name = "close_time")
    private LocalTime closeTime;

    /**
     * Super-admin approval state. Regular ADMIN-created binges start as
     * {@link BingeApprovalStatus#PENDING_APPROVAL}; SUPER_ADMIN-created and
     * pre-existing binges are {@link BingeApprovalStatus#APPROVED}. Customers
     * only see APPROVED binges.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private BingeApprovalStatus status = BingeApprovalStatus.APPROVED;

    /** User id of the SUPER_ADMIN who approved or rejected this binge (audit). */
    @Column(name = "approval_decided_by")
    private Long approvalDecidedBy;

    /** Timestamp of the approve/reject decision (audit). */
    @Column(name = "approval_decided_at")
    private LocalDateTime approvalDecidedAt;

    /** Optional reason captured when a super-admin rejects a binge request. */
    @Column(name = "approval_rejection_reason", length = 500)
    private String approvalRejectionReason;

    /**
     * Timestamp of the first active event type ever created on this binge.
     * Set once and never cleared. Used by the grace-period scheduler to
     * determine if a freshly-approved binge has become "operational" within
     * the 24-hour SLA.
     */
    @Column(name = "first_event_created_at")
    private LocalDateTime firstEventCreatedAt;

    /**
     * When set, the courtesy 12-hour grace-period warning has already been
     * delivered to the requesting admin and we won't spam them again.
     */
    @Column(name = "grace_warning_sent_at")
    private LocalDateTime graceWarningSentAt;

    /**
     * If non-null, the scheduler auto-deactivated this binge because it had
     * no events 24 h after approval. Surfaces an inline banner in the admin
     * UI explaining why the binge is paused.
     */
    @Column(name = "auto_deactivated_at")
    private LocalDateTime autoDeactivatedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
