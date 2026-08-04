package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V84 — the rules that decide <em>when</em> a slot may be booked and <em>how long</em>
 * a booking may run. Kept out of {@code BookingService} deliberately: that class is
 * already 5,000+ lines and the audit names it the main source of regressions.
 *
 * <p>Two rules, both venue-configured:
 * <ul>
 *   <li><b>Booking window</b> (gap G5) — a minimum notice period and a maximum
 *       advance horizon. Direct customers rarely book at 23:58 for 00:30; a sales
 *       channel will, because it has no idea the venue is shut.</li>
 *   <li><b>Permitted durations</b> (decision B5) — an optional allow-list of up to
 *       four durations. Free choice across 30..720 in 30-minute steps means 24
 *       options per start time, which a reseller catalogue has to enumerate as
 *       discrete products.</li>
 * </ul>
 *
 * <p>All time comparisons are made in the <b>venue's own timezone</b>. Using server
 * time would make "2 hours' notice" mean different things in different countries,
 * which is precisely the bug a multi-country venue platform cannot afford.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingWindowPolicy {

    /** B5: more than this and a channel's option picker stops being usable. */
    public static final int MAX_PERMITTED_DURATIONS = 4;

    private final VenueClockService venueClock;

    // ── G5: booking window ───────────────────────────────────────────────

    /**
     * Reject a booking that starts sooner than the venue's minimum notice, or
     * further ahead than it publishes.
     *
     * @param platformHorizonDays the global fallback used when the venue sets no
     *        {@code maxAdvanceDays} — keeps existing behaviour for unconfigured venues
     */
    public void assertWithinBookingWindow(Binge binge, LocalDate bookingDate,
                                          LocalTime startTime, int platformHorizonDays) {
        if (binge == null || bookingDate == null || startTime == null) return;

        LocalDateTime venueNow = LocalDateTime.now(venueClock.zoneOf(binge.getId()));
        LocalDateTime start = LocalDateTime.of(bookingDate, startTime);

        int minNotice = Math.max(binge.getMinNoticeMinutes(), 0);
        if (minNotice > 0) {
            LocalDateTime earliest = venueNow.plusMinutes(minNotice);
            if (start.isBefore(earliest)) {
                throw new BusinessException(
                    "This venue needs at least " + describeMinutes(minNotice)
                    + " notice. The earliest slot you can book is "
                    + earliest.toLocalDate() + " at " + earliest.toLocalTime().withSecond(0).withNano(0) + ".");
            }
        }

        int horizonDays = binge.getMaxAdvanceDays() != null
            ? binge.getMaxAdvanceDays()
            : platformHorizonDays;
        if (horizonDays > 0) {
            LocalDate latest = venueNow.toLocalDate().plusDays(horizonDays);
            if (bookingDate.isAfter(latest)) {
                throw new BusinessException(
                    "This venue takes bookings up to " + horizonDays + " days ahead (through "
                    + latest + "). Please choose an earlier date.");
            }
        }
    }

    // ── B5: permitted durations ──────────────────────────────────────────

    /**
     * The durations this event type may be booked for, in minutes and ascending
     * order. Empty means "no allow-list configured" — any 30-minute multiple within
     * the event type's own min/max hours remains valid, which is the pre-V84 behaviour.
     */
    public List<Integer> permittedDurations(EventType eventType) {
        return parseDurations(eventType == null ? null : eventType.getPermittedDurationsCsv());
    }

    /** Reject a duration the venue has not published for this event type. */
    public void assertDurationPermitted(EventType eventType, int durationMinutes) {
        List<Integer> allowed = permittedDurations(eventType);
        if (allowed.isEmpty() || allowed.contains(durationMinutes)) return;

        throw new BusinessException(
            "This experience is offered in fixed lengths: "
            + String.join(", ", allowed.stream().map(BookingWindowPolicy::describeMinutes).toList())
            + ". Please pick one of those.");
    }

    /**
     * Parse and validate operator input, returning the canonical CSV to persist.
     * Returns {@code null} for "no allow-list", which is a meaningful value —
     * it restores free choice rather than meaning "unset by accident".
     */
    public String normaliseDurationsForSave(List<Integer> durations, int minHours, int maxHours) {
        if (durations == null || durations.isEmpty()) return null;

        Set<Integer> unique = new LinkedHashSet<>(durations);
        if (unique.size() > MAX_PERMITTED_DURATIONS) {
            throw new BusinessException(
                "At most " + MAX_PERMITTED_DURATIONS + " durations can be offered — sales channels show "
                + "each one as a separate option, and more than that makes the picker unusable.");
        }
        int minMinutes = Math.max(minHours, 1) * 60;
        int maxMinutes = Math.max(maxHours, 1) * 60;
        for (Integer d : unique) {
            if (d == null || d < 30 || d > 720) {
                throw new BusinessException("Each duration must be between 30 minutes and 12 hours.");
            }
            if (d % 30 != 0) {
                throw new BusinessException("Each duration must be a whole number of 30-minute blocks.");
            }
            if (d < minMinutes || d > maxMinutes) {
                throw new BusinessException(
                    describeMinutes(d) + " is outside this event type's " + minHours + "–" + maxHours
                    + " hour range. Widen the range first, or pick a duration inside it.");
            }
        }
        return unique.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    private static List<Integer> parseDurations(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                // A malformed stored value must not break booking outright — degrade to
                // "no allow-list" (the permissive pre-V84 behaviour) and make it visible.
                log.warn("Ignoring malformed permitted_durations_csv value '{}'", csv);
                return List.of();
            }
        }
        out.sort(Integer::compareTo);
        return List.copyOf(out);
    }

    /** "90 minutes" / "2 hours" / "2h 30m" — used in customer-facing errors, so it must read naturally. */
    public static String describeMinutes(int minutes) {
        if (minutes < 60) return minutes + " minutes";
        int hours = minutes / 60;
        int rem = minutes % 60;
        String h = hours + (hours == 1 ? " hour" : " hours");
        return rem == 0 ? h : h + " " + rem + " minutes";
    }

    /** Convenience for callers holding a raw CSV (e.g. DTO mapping). */
    public static List<Integer> parse(String csv) {
        return parseDurations(csv);
    }

    /** Convenience for DTO mapping in the other direction. */
    public static String toCsv(List<Integer> durations) {
        if (durations == null || durations.isEmpty()) return null;
        return durations.stream().sorted().distinct().map(String::valueOf)
            .reduce((a, b) -> a + "," + b).orElse(null);
    }

}
