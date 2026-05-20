package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Threaded support note attached to a booking. Replaces (and supersedes) the
 * legacy single-string {@code Booking.adminNotes} TEXT column, which had three
 * problems audited under Item 24:
 *
 * <ol>
 *   <li>Each save <em>overwrote</em> the column — older notes were lost.</li>
 *   <li>No author / timestamp on individual entries.</li>
 *   <li>No way to mark a note customer-visible vs internal-only.</li>
 * </ol>
 *
 * Each row is a single immutable-by-default note. Editing is allowed only by
 * the original author and within {@link #EDIT_WINDOW_MINUTES}; afterwards the
 * note is locked and a new note must be added instead — preserving the audit
 * trail. Deletes are soft (status flag) and reserved to super-admins.
 */
@Entity
@Table(name = "booking_notes", indexes = {
    @Index(name = "idx_booking_notes_ref", columnList = "bookingRef,createdAt"),
    @Index(name = "idx_booking_notes_pinned", columnList = "bookingRef,pinned,createdAt")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingNote {

    /** Edit window after which a note becomes immutable (operator audit trust). */
    public static final int EDIT_WINDOW_MINUTES = 15;

    public enum Visibility {
        /** Visible only to admins / staff. Default. */
        INTERNAL,
        /**
         * Visible to the customer too — e.g. a goodwill comp explanation that
         * we want to surface in the customer's "My bookings" timeline. The
         * customer-facing endpoint filters on this.
         */
        CUSTOMER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String bookingRef;

    @Column(nullable = false)
    private Long bingeId;

    /** Admin user id of the note's author. */
    @Column(nullable = false)
    private Long authorAdminId;

    /** Display name captured at write time so deleted admin accounts still render. */
    @Column(nullable = false, length = 100)
    private String authorName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private Visibility visibility = Visibility.INTERNAL;

    /** Pinned notes are rendered at the top of the booking detail panel. */
    @Builder.Default
    @Column(nullable = false)
    private boolean pinned = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean edited = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
