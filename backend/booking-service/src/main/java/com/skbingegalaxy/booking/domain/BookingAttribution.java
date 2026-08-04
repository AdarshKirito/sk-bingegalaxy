package com.skbingegalaxy.booking.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * A captured marketing referral, normalised and validated in one place.
 *
 * <p><b>Why this is a domain type and not three loose fields.</b> Attribution arrives
 * from an untrusted source — query parameters on a public URL that anyone can craft.
 * The rules that make it safe (canonical form, length bounds, a validity window) have
 * to hold at every entry point, and a record with a single factory is how that stops
 * being a convention each caller must remember.
 *
 * <p><b>It is a reporting dimension and nothing else.</b> Nothing here participates in
 * pricing, availability or eligibility, and no method on this class returns anything a
 * booking decision could branch on. That constraint is deliberate: attribution comes
 * from the customer's own URL, so letting it influence what they pay would let a
 * customer choose their own discount.
 */
public record BookingAttribution(String source, String ref, LocalDateTime capturedAt) {

    /**
     * Last non-direct touch wins, within 30 days. A referral older than this is treated
     * as organic rather than credited — otherwise a single click would keep claiming
     * credit for every booking a customer makes for the rest of the session's life.
     */
    public static final Duration WINDOW = Duration.ofDays(30);

    /** Matches {@code bookings.attribution_source VARCHAR(64)}. */
    public static final int MAX_SOURCE_LENGTH = 64;
    /** Matches {@code bookings.attribution_ref VARCHAR(128)}. */
    public static final int MAX_REF_LENGTH = 128;

    /**
     * Build a validated attribution, or {@code null} when there is nothing worth
     * recording.
     *
     * <p>Returns null — rather than throwing — for absent or unusable input on purpose.
     * A malformed marketing parameter must never fail a booking. The customer is trying
     * to buy something; losing the analytics dimension is an acceptable cost, losing the
     * sale is not.
     *
     * <p>An <b>unrecognised</b> source is still recorded, verbatim (after
     * canonicalisation). Discarding sources we do not yet have a name for would mean
     * the first data about a new channel is silently thrown away — which is precisely
     * the data needed to decide whether that channel is worth building.
     */
    public static BookingAttribution of(String rawSource, String rawRef,
                                        LocalDateTime capturedAt, LocalDateTime now) {
        String source = canonicalSource(rawSource);
        if (source == null) {
            // No source means no bucket to report into; a bare click id belongs to
            // nobody and would sit outside every "conversions by source" total.
            return null;
        }
        if (capturedAt != null && now != null && isExpired(capturedAt, now)) {
            return null;
        }
        return new BookingAttribution(source, trimToLimit(rawRef, MAX_REF_LENGTH), capturedAt);
    }

    /**
     * Lowercased and trimmed, matching {@code ck_booking_attribution_source_canonical}.
     * Over-long values are truncated rather than rejected: the column is bounded, and a
     * 200-character utm_source is a caller bug that should not cost the booking.
     */
    public static String canonicalSource(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_SOURCE_LENGTH
            ? trimmed.substring(0, MAX_SOURCE_LENGTH)
            : trimmed;
    }

    private static String trimToLimit(String raw, int limit) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    /**
     * A capture timestamp in the future is treated as expired, not as valid forever.
     * The value comes from the customer's own browser clock, so it is not trustworthy;
     * failing closed costs one reporting row, while trusting it would let a skewed or
     * forged clock hold attribution open indefinitely.
     */
    public static boolean isExpired(LocalDateTime capturedAt, LocalDateTime now) {
        if (capturedAt == null || now == null) return false;
        if (capturedAt.isAfter(now)) return true;
        return Duration.between(capturedAt, now).compareTo(WINDOW) > 0;
    }
}
