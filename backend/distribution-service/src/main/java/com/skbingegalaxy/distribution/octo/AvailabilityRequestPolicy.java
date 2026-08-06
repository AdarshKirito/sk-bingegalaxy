package com.skbingegalaxy.distribution.octo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Bounds an inbound availability query. This IS the DIST-R6 mitigation.
 *
 * <p><b>The risk it exists for.</b> Resellers poll calendars for 365 days across every
 * product, on a schedule, from several resellers at once. Answered naively that is one
 * database query per product per day per reseller, and it melts booking-service — the
 * service every direct customer booking also depends on. A distribution feature taking
 * down the core product is the worst possible failure, and it happens under normal
 * reseller behaviour rather than abuse.
 *
 * <p><b>Clamped, not rejected.</b> A reseller asking for 365 days is behaving exactly as
 * OCTO expects; refusing would break a conforming client. Returning the first
 * {@link #MAX_CALENDAR_DAYS} instead answers the question honestly at a bounded cost, and
 * the reseller pages for the rest. Rejecting is reserved for a range that makes no sense
 * at all, which is a bug on their side worth surfacing.
 */
public final class AvailabilityRequestPolicy {

    private AvailabilityRequestPolicy() {}

    /**
     * The coarse calendar answers "which days have anything at all", so a wide window is
     * cheap per day and this can be generous.
     */
    public static final int MAX_CALENDAR_DAYS = 62;

    /**
     * The fine query returns every slot with its price, so its cost per day is far
     * higher. OCTO's whole reason for splitting the two endpoints is that a client
     * should narrow with the calendar first and ask for detail only where it matters.
     */
    public static final int MAX_DETAIL_DAYS = 7;

    public record Window(LocalDate from, LocalDate to, boolean clamped) {}

    /**
     * @throws IllegalArgumentException when the range is nonsensical — {@code to} before
     *         {@code from}, or a missing bound. That is a client bug, and answering it
     *         with a silently corrected window would hide it.
     */
    public static Window clamp(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both localDateStart and localDateEnd are required.");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("localDateEnd cannot be before localDateStart.");
        }
        long span = ChronoUnit.DAYS.between(from, to);
        if (span < maxDays) {
            return new Window(from, to, false);
        }
        // Inclusive of `from`, so a maxDays window spans maxDays dates.
        return new Window(from, from.plusDays(maxDays - 1L), true);
    }
}
