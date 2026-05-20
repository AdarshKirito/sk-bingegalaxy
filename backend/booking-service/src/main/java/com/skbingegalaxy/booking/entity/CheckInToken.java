package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Single-use check-in token (QR or OTP) bound to a {@link Booking}.
 *
 * <p>For QR tokens the {@code tokenValue} is a 32-char URL-safe random string
 * that is itself the secret (delivered via email/QR image).
 *
 * <p>For OTP tokens the {@code tokenValue} stores ONLY the SHA-256 hex digest
 * of the 6-digit code so the live code is never persisted.
 *
 * <p>Lifecycle: {@code issued} → {@code consumed} (terminal) or {@code expired}
 * (passive, by {@code expiresAt}).
 */
@Entity
@Table(name = "check_in_tokens")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CheckInToken {

    public enum TokenType { QR, OTP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, length = 20)
    private String bookingRef;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 8)
    private TokenType tokenType;

    @Column(name = "token_value", nullable = false, length = 128)
    private String tokenValue;

    @Column(name = "issued_by", length = 150)
    private String issuedBy;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_by", length = 150)
    private String consumedBy;

    @Column(name = "consumed_ip", length = 64)
    private String consumedIp;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "binge_id")
    private Long bingeId;

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired(LocalDateTime now) { return now.isAfter(expiresAt); }
    public boolean isUsable(LocalDateTime now) { return !isConsumed() && !isExpired(now); }
}
