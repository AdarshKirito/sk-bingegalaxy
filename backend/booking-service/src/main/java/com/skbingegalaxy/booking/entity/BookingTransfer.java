package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 2-phase booking transfer offer. The original owner creates a {@code PENDING}
 * row with an opaque accept-token; the recipient lands on a token URL and
 * accepts/declines. A scheduler expires stale {@code PENDING} rows.
 *
 * <p>The actual ownership change on the parent {@link Booking} only happens at
 * accept time, never at request time — this is the "safety" the user asked for.
 */
@Entity
@Table(name = "booking_transfers", indexes = {
    @Index(name = "idx_booking_transfers_booking_ref", columnList = "booking_ref"),
    @Index(name = "idx_booking_transfers_from_customer_created",
           columnList = "from_customer_id, created_at"),
    @Index(name = "idx_booking_transfers_status_expires",
           columnList = "status, expires_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingTransfer {

    public enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, length = 20)
    private String bookingRef;

    @Column(name = "binge_id", nullable = false)
    private Long bingeId;

    @Column(name = "from_customer_id", nullable = false)
    private Long fromCustomerId;

    @Column(name = "from_customer_name", nullable = false, length = 150)
    private String fromCustomerName;

    @Column(name = "from_customer_email", nullable = false, length = 150)
    private String fromCustomerEmail;

    @Column(name = "to_name", nullable = false, length = 150)
    private String toName;

    @Column(name = "to_email", nullable = false, length = 150)
    private String toEmail;

    @Column(name = "to_phone", length = 20)
    private String toPhone;

    @Column(name = "to_phone_country_code", length = 8)
    private String toPhoneCountryCode;

    /** Populated on accept if the recipient is signed in. Optional. */
    @Column(name = "to_customer_id")
    private Long toCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    /** Single-use bearer token. Sent in the email link. */
    @Column(name = "accept_token", nullable = false, unique = true, length = 80)
    private String acceptToken;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;
}
