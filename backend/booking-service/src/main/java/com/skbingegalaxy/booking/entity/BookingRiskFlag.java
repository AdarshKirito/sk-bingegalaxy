package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A persisted "risk signal" attached to a booking that an admin should
 * eyeball before, during or after the visit.
 *
 * <h3>Why this entity exists</h3>
 * The booking lifecycle already has rich event logging
 * ({@code BookingEventLog}) and per-binge freeze rules
 * ({@code CustomerBingeFreeze}). What was missing was a structured queue of
 * <em>booking-level</em> risk findings that:
 *
 * <ul>
 *   <li>survives across binges (a {@code customerId} that's flagged on one
 *       binge may want to be reviewed on another),</li>
 *   <li>is filterable by severity / acknowledged-state for the operator
 *       inbox (Item 23 — "fraud / abuse detection"),</li>
 *   <li>can be created by automated rules <em>and</em> by humans
 *       ({@link Source#SYSTEM} vs {@link Source#ADMIN}),</li>
 *   <li>doesn't pollute the existing event log with non-state-transition
 *       rows.</li>
 * </ul>
 *
 * <p>The flag is purely informational by default — flagging a booking does
 * NOT auto-cancel or block the customer. Admins can act on flags via the
 * existing freeze + cancel workflows. A future iteration may add an
 * "auto-block" boolean for the highest-severity rules.
 */
@Entity
@Table(name = "booking_risk_flags", indexes = {
    @Index(name = "idx_risk_flag_booking", columnList = "bookingRef"),
    @Index(name = "idx_risk_flag_customer", columnList = "customerId"),
    @Index(name = "idx_risk_flag_open",     columnList = "acknowledged,severity,createdAt"),
    @Index(name = "idx_risk_flag_binge",    columnList = "bingeId,acknowledged,severity")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingRiskFlag {

    public enum Severity {
        /** Operator awareness only (e.g. first-time customer with large party). */
        LOW,
        /** Should be reviewed before check-in (e.g. multiple recent cancels). */
        MEDIUM,
        /** Likely abuse pattern — admin should consider freezing the customer. */
        HIGH
    }

    public enum RuleCode {
        /** Same phone number used by ≥ N distinct customer accounts. */
        SHARED_PHONE_MULTIPLE_ACCOUNTS,
        /** Same email used by ≥ N distinct customer accounts. */
        SHARED_EMAIL_MULTIPLE_ACCOUNTS,
        /** Customer has cancelled ≥ N pending bookings recently across all binges. */
        REPEATED_PENDING_CANCELLATIONS,
        /** Customer has been marked NO_SHOW ≥ N times recently. */
        REPEATED_NO_SHOWS,
        /** Booking value exceeds {@code app.risk.high-value-threshold}. */
        UNUSUALLY_HIGH_VALUE,
        /** Burst of bookings from the same customer in a short interval. */
        RAPID_REBOOKING_BURST,
        /** Created by an admin / support agent via the UI. */
        MANUAL
    }

    public enum Source { SYSTEM, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Booking reference (e.g. SKBG24XXXXXXXX). Stored as the human ref, not FK,
     *  so the flag survives a hard-delete of the booking row. */
    @Column(nullable = false, length = 30)
    private String bookingRef;

    @Column(nullable = false)
    private Long bingeId;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RuleCode ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Source source = Source.SYSTEM;

    /** Free-text reason / supporting details (rendered in the operator UI). */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /**
     * Optional structured payload (JSON) the rule wants to surface — e.g.
     * {@code {"sharedPhone":"+919999999999","accounts":[12,34,56]}}. Kept as
     * TEXT instead of JSONB so this entity stays portable.
     */
    @Column(columnDefinition = "TEXT")
    private String evidence;

    /** Admin user who created the flag (null when {@link Source#SYSTEM}). */
    private Long createdByAdminId;

    @Builder.Default
    @Column(nullable = false)
    private boolean acknowledged = false;

    private Long acknowledgedByAdminId;
    private LocalDateTime acknowledgedAt;
    @Column(columnDefinition = "TEXT")
    private String acknowledgedNote;

    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
