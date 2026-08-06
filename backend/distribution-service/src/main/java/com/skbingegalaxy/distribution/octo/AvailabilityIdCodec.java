package com.skbingegalaxy.distribution.octo;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The supplier-issued token that names exactly which window a reseller is buying.
 *
 * <p>OCTO leaves {@code availabilityId} opaque and lets the supplier define it. Two
 * shapes were possible and the choice matters:
 *
 * <ul>
 *   <li><b>A database key</b> pointing at a stored availability row. Rejected: this
 *       context stores no availability. A key would require one, and a stored copy of
 *       somebody else's inventory is the second-inventory-truth failure the whole
 *       distribution design exists to avoid — the same one the V81 database backstop
 *       protects against.</li>
 *   <li><b>A self-describing token</b> carrying the start instant and the duration.
 *       Chosen. It needs no storage, it survives a restart, and a reseller replaying a
 *       token from last week gets a clean rejection from the booking rules rather than
 *       a lookup miss that reads as a supplier bug.</li>
 * </ul>
 *
 * <p>Format: {@code <ISO local date-time>|<duration minutes>}, e.g.
 * {@code 2026-08-10T18:00|120}. The time is the VENUE's local time — the same basis the
 * availability endpoints answer in, so no timezone is implied and none can be lost.
 *
 * <p>Signing was considered and deliberately not added. The token grants nothing on its
 * own: a reseller can only book windows that pass every ordinary check — approval status,
 * the advisory slot lock, occupancy windows, turnover buffers, operating hours, the
 * booking window and the database backstop — all of which run exactly as they do for a
 * customer. A forged token buys a rejection, not a booking.
 */
public final class AvailabilityIdCodec {

    private AvailabilityIdCodec() {}

    private static final char SEPARATOR = '|';

    /** Matches {@code ChannelReservationRequest}: 30 minutes to 12 hours. */
    static final int MIN_DURATION_MINUTES = 30;
    static final int MAX_DURATION_MINUTES = 720;

    /**
     * STRICT, and {@code uuuu} rather than {@code yyyy} because strict resolution
     * requires the proleptic year field.
     *
     * <p>The default SMART resolver quietly moves an impossible date to the nearest real
     * one — {@code 2026-02-31} becomes 28 February. A reseller would then be sold, and
     * charged for, a window three days from the one it asked for, and every check
     * downstream would defend that window perfectly.
     */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    /** A decoded window. */
    public record Window(LocalDateTime start, int durationMinutes) {}

    public static String encode(LocalDateTime start, int durationMinutes) {
        return FORMAT.format(start) + SEPARATOR + durationMinutes;
    }

    /**
     * Decode, or empty when the token is not one this supplier issued.
     *
     * <p>Returns empty rather than throwing: a malformed availabilityId is a message the
     * inbox must REJECT with a readable reason, not an exception that becomes a retry.
     * Retrying it would fail identically forever.
     */
    public static Optional<Window> decode(String availabilityId) {
        if (availabilityId == null) return Optional.empty();
        String token = availabilityId.trim();
        int sep = token.indexOf(SEPARATOR);
        if (sep <= 0 || sep == token.length() - 1) return Optional.empty();

        LocalDateTime start;
        try {
            start = LocalDateTime.parse(token.substring(0, sep), FORMAT);
        } catch (DateTimeException e) {
            // Covers DateTimeParseException and the resolution failures (e.g. 31 April)
            // that surface as the parent type.
            return Optional.empty();
        }

        int minutes;
        try {
            minutes = Integer.parseInt(token.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // Bounded here as well as downstream. booking-service validates the same range,
        // but a token claiming a 40-day booking should be refused by the context that
        // issued the token format rather than travel a service hop first.
        if (minutes < MIN_DURATION_MINUTES || minutes > MAX_DURATION_MINUTES) {
            return Optional.empty();
        }
        // Whole-space hire is sold in half-hour steps; a 47-minute window is not
        // something any venue's slot grid can express.
        if (minutes % MIN_DURATION_MINUTES != 0) return Optional.empty();

        return Optional.of(new Window(start, minutes));
    }
}
