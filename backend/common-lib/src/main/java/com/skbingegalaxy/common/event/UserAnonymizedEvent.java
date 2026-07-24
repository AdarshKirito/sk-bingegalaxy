package com.skbingegalaxy.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Published by auth-service after a user's right-to-erasure anonymization
 * completes ({@code UserAnonymizationService}). Booking, payment, and
 * notification services each hold denormalized PII snapshots (customer
 * name/email/phone on bookings, payments, waitlist entries, Mongo
 * notification documents…) that auth's anonymization cannot reach — every
 * consumer of this event redacts its own copies.
 *
 * <p>{@code originalEmail} is included deliberately: notification-service
 * keys its documents by recipient email, not user id, so the pre-anonymization
 * email is the only way to locate them. Consumers must not persist it beyond
 * the redaction it enables.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAnonymizedEvent {

    private Long userId;

    /** The user's email BEFORE anonymization — needed to locate email-keyed copies. */
    private String originalEmail;

    /** Placeholder email now stored on the auth user (e.g. deleted-42@anonymized.invalid). */
    private String anonymizedEmail;

    private LocalDateTime anonymizedAt;
}
