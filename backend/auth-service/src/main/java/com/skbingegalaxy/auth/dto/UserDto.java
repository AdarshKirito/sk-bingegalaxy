package com.skbingegalaxy.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skbingegalaxy.common.enums.UserRole;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String phoneCountryCode;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String country;
    private String postalCode;
    private String preferredExperience;
    private String vibePreference;
    private Integer reminderLeadDays;
    private String birthdayMonth;
    private Integer birthdayDay;
    private String anniversaryMonth;
    private Integer anniversaryDay;
    private String notificationChannel;
    private boolean receivesOffers;
    private boolean weekendAlerts;
    private boolean conciergeSupport;
    private UserRole role;
    private boolean active;
    private LocalDateTime createdAt;

    // ── Security posture (V7) ────────────────────────────────
    private boolean emailVerified;
    private boolean mfaEnabled;
    private LocalDateTime lastPasswordChangeAt;

    /** True while the account still holds an admin-issued temporary password. */
    private boolean mustChangePassword;

    /**
     * The one-time temporary password, returned ONLY by the admin "create
     * customer" / "resend temp password" responses so the front-desk admin can
     * read it out at the counter. Null on every other DTO. Never persisted in
     * plaintext — it is hashed for storage and the customer also receives it by
     * email + SMS.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String temporaryPassword;
}
