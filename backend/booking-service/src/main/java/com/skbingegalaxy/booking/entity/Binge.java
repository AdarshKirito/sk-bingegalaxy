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

    /**
     * ISO-3166-1 alpha-2 country code, e.g. "IN", "US". REQUIRED (V79).
     *
     * <p>Load-bearing rather than descriptive: {@link #currency} is derived from
     * it, the timezone is seeded from it, tax rules are selected by it, and the
     * payment methods offered at checkout resolve from it. Legacy NULL rows were
     * backfilled from currency by {@code V79__binge_country_required}.
     */
    @Column(length = 2, nullable = false)
    private String country;

    /**
     * ISO-4217 currency code for this binge, DERIVED from {@link #country}
     * (see {@code CountryCurrency}). Every price, tax and payment for this binge is
     * denominated in this currency — the customer never chooses one. Only a SUPER_ADMIN
     * can change it (by changing the country); a regular admin must request the change.
     */
    @Column(length = 3, nullable = false)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /**
     * WGS-84 latitude in decimal degrees (-90..90). Null when the venue has not been
     * geocoded yet. Together with {@link #longitude} it powers the proximity ranking
     * behind {@code GET /api/v1/bookings/binges/nearby}. Un-geocoded venues never
     * appear in proximity results; they remain reachable via the alphabetical listing.
     */
    @Column
    private Double latitude;

    /** WGS-84 longitude in decimal degrees (-180..180). See {@link #latitude}. */
    @Column
    private Double longitude;

    /**
     * IANA timezone identifier for this venue (e.g. "Asia/Kolkata", "America/New_York",
     * "Europe/London"). Used by all booking-date validation, check-in window arithmetic,
     * and tax-rule effective-date evaluation. Never rely on the JVM default timezone.
     */
    @Column(length = 64, nullable = false)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(nullable = false)
    private Long adminId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * V56: when true, the customer must pick a venue room before
     * progressing past the room step in the booking wizard. Defaults to
     * false to preserve existing behaviour.
     */
    @Column(name = "room_selection_required", nullable = false)
    @Builder.Default
    private boolean roomSelectionRequired = false;

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

    /**
     * V78: the PUBLIC support phone doubles as the venue's WhatsApp contact.
     * When true and {@link #supportWhatsapp} is blank, customer surfaces use
     * {@link #supportPhone} for the WhatsApp channel.
     */
    @Column(name = "support_phone_is_whatsapp", nullable = false)
    @Builder.Default
    private boolean supportPhoneIsWhatsapp = false;

    // ── V78: PERSONAL / owner contact — INTERNAL ONLY ────────────────────
    // How the platform (super-admins) reaches the venue's admin. Never exposed
    // through public DTOs; customers only ever see the support_* channel.

    /** Owner/admin personal email for platform-to-admin contact. */
    @Column(name = "owner_email", length = 150)
    private String ownerEmail;

    /** Owner/admin personal phone for platform-to-admin contact. */
    @Column(name = "owner_phone", length = 20)
    private String ownerPhone;

    /** E.164 dial prefix for {@link #ownerPhone}, e.g. "+91". */
    @Column(name = "owner_phone_country_code", length = 8)
    private String ownerPhoneCountryCode;

    /** Whether {@link #ownerPhone} is also reachable on WhatsApp. */
    @Column(name = "owner_phone_is_whatsapp", nullable = false)
    @Builder.Default
    private boolean ownerPhoneIsWhatsapp = false;

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

    /**
     * Max NO_SHOW bookings (system marked by daily audit) within
     * {@link #freezeDurationMinutes} before a NO_SHOW_PATTERN freeze is applied.
     * Set to 0 to disable NO_SHOW-based freezes while keeping the other
     * trigger types active. Default 3 mirrors the cancel/timeout knobs.
     */
    @Column(name = "max_no_shows_before_freeze", nullable = false)
    @Builder.Default
    private int maxNoShowsBeforeFreeze = 3;

    /**
     * How many concurrent unpaid (PENDING) bookings a customer may hold at this venue
     * before new bookings are stopped with a "complete or cancel them first" message.
     * Admin-configurable per binge (industry pattern: a merchant knob, not a hardcode);
     * takes precedence over the global {@code app.booking.max-pending-per-customer}.
     * Clamped to [1, 50] at write time.
     */
    @Column(name = "max_unpaid_bookings_per_customer", nullable = false)
    @Builder.Default
    private int maxUnpaidBookingsPerCustomer = 2;

    /**
     * Master switch for the tax engine at this venue — SUPER-ADMIN controlled.
     * When false, every tax entry point (customer preview, checkout, booking
     * creation by any role, update, reschedule, recurring) computes zero tax.
     * Gated centrally in {@link com.skbingegalaxy.booking.service.TaxService}.
     */
    @Column(name = "taxes_enabled", nullable = false)
    @Builder.Default
    private boolean taxesEnabled = true;

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

    // ── V81: turnover buffers ────────────────────────────────────────────
    /**
     * Venue-wide default prep time (minutes) reserved BEFORE every booking.
     * An {@link EventType} may override it; a NULL override there inherits
     * this value. Together with {@link #defaultCleanupMinutes} it widens a
     * booking's <em>occupancy window</em> beyond its billable interval:
     *
     * <pre>[ start - setup , start + duration + cleanup )</pre>
     *
     * Conflict detection compares occupancy windows, so a venue that sets a
     * 45-minute cleanup can never have two parties sold back-to-back in the
     * same room. Range 0..240, enforced by a DB CHECK.
     */
    @Column(name = "default_setup_minutes", nullable = false)
    @Builder.Default
    private int defaultSetupMinutes = 0;

    /**
     * Venue-wide default reset/turnover time (minutes) reserved AFTER every booking.
     * See {@link #defaultSetupMinutes}.
     *
     * <p>V83 sets the <em>column</em> default to 30 for newly created venues. Venues
     * that predate V83 keep whatever they had (0 unless configured) — backfilling
     * them would retroactively widen the occupancy of bookings they have already
     * sold and could make those overlap each other.
     */
    @Column(name = "default_cleanup_minutes", nullable = false)
    @Builder.Default
    private int defaultCleanupMinutes = DEFAULT_CLEANUP_MINUTES_FOR_NEW_VENUES;

    /**
     * The protective turnover default applied to venues created from V83 onward.
     * A celebration space realistically cannot be reset in under half an hour, and
     * a venue that genuinely needs none can set 0 explicitly.
     */
    public static final int DEFAULT_CLEANUP_MINUTES_FOR_NEW_VENUES = 30;

    /**
     * When an operator explicitly confirmed this venue's turnover buffers.
     *
     * <p>NULL means nobody has decided yet — the admin console prompts, and a venue
     * should not be published to a sales channel until it is set. <b>Choosing zero
     * is a valid answer;</b> what this records is that the choice was made, not that
     * it was non-zero. Distinguishing "deliberately zero" from "never looked at"
     * is the whole point — without it, a 0 buffer is indistinguishable from an
     * unconfigured venue.
     */
    @Column(name = "turnover_policy_reviewed_at")
    private LocalDateTime turnoverPolicyReviewedAt;

    /** Admin/super-admin user id who confirmed the turnover policy (audit). */
    @Column(name = "turnover_policy_reviewed_by")
    private Long turnoverPolicyReviewedBy;

    // ── V84: booking window (gap G5) ─────────────────────────────────────
    /**
     * Minimum lead time in minutes before a booking may start. 0 allows
     * same-minute booking (the pre-V84 behaviour). Evaluated against the venue's
     * own clock, so "2 hours' notice" means the same thing in every country.
     */
    @Column(name = "min_notice_minutes", nullable = false)
    @Builder.Default
    private int minNoticeMinutes = 0;

    /**
     * How far ahead this venue publishes availability, in days. NULL inherits the
     * platform-wide {@code app.booking.max-booking-horizon-days} (default 365).
     */
    @Column(name = "max_advance_days")
    private Integer maxAdvanceDays;
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
     * Optional per-day operating hours as a JSON array (see V66). Each element:
     * {@code {"dayOfWeek":1-7,"closed":bool,"openTime":"HH:mm","closeTime":"HH:mm"}}
     * with {@code dayOfWeek} following {@link java.time.DayOfWeek} (1=Mon..7=Sun).
     * When present it OVERRIDES {@link #openTime}/{@link #closeTime} for the matching
     * day; when null/blank the single open/close pair (then the global default)
     * applies. Parsed via {@code OpeningHoursCodec}.
     */
    @Column(name = "opening_hours_json", columnDefinition = "TEXT")
    private String openingHoursJson;

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

    /**
     * Free-text access/ops remarks shown on the binge About page. Edited by
     * SUPER_ADMIN alongside the module permission matrix (V71).
     */
    @Column(name = "access_remarks", length = 1000)
    private String accessRemarks;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
