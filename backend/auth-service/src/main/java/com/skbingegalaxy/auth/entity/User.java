package com.skbingegalaxy.auth.entity;

import com.skbingegalaxy.common.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
    @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(unique = true, length = 20)
    private String phone;

    /** E.164 dial prefix including the leading '+', e.g. "+91". Null until the user supplies a phone. */
    @Column(name = "phone_country_code", length = 8)
    private String phoneCountryCode;

    // ── Postal address (optional; populated via profile / registration flows) ──
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

    @Column(length = 100)
    private String preferredExperience;

    @Column(length = 120)
    private String vibePreference;

    @Column
    @Builder.Default
    private Integer reminderLeadDays = 14;

    @Column(length = 20)
    private String birthdayMonth;

    @Column
    private Integer birthdayDay;

    @Column(length = 20)
    private String anniversaryMonth;

    @Column
    private Integer anniversaryDay;

    @Column
    private Integer birthdayReminderSentYear;

    @Column
    private Integer anniversaryReminderSentYear;

    @Column(length = 20)
    @Builder.Default
    private String notificationChannel = "EMAIL";

    @Column
    @Builder.Default
    private Boolean receivesOffers = true;

    @Column
    @Builder.Default
    private Boolean weekendAlerts = true;

    @Column
    @Builder.Default
    private Boolean conciergeSupport = true;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── Account lockout fields ───────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column
    private LocalDateTime lockedUntil;

    // ── Email verification (V7) ──────────────────────────────
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    // ── MFA / TOTP (V7) ──────────────────────────────────────
    @Column(name = "mfa_enabled", nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    /** Base32-encoded TOTP secret; null until enrolment completes. */
    @Column(name = "mfa_secret", length = 64)
    private String mfaSecret;

    @Column(name = "mfa_enrolled_at")
    private LocalDateTime mfaEnrolledAt;

    /** Comma-separated list of SHA-256 hashes of single-use recovery codes. */
    @Column(name = "mfa_recovery_codes_hash", columnDefinition = "TEXT")
    private String mfaRecoveryCodesHash;

    @Column(name = "last_password_change_at")
    private LocalDateTime lastPasswordChangeAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isAccountLocked() {
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }

    public void incrementFailedAttempts() {
        this.failedLoginAttempts++;
    }

    public void resetFailedAttempts() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void lockAccount(int lockMinutes) {
        this.lockedUntil = LocalDateTime.now().plusMinutes(lockMinutes);
    }
}
