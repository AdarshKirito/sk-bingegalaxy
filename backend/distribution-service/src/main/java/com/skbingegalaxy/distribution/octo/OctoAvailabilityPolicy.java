package com.skbingegalaxy.distribution.octo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns SK Binge's 30-minute slot grid into the bookable units OCTO expects.
 *
 * <p><b>Why this translation has to exist.</b> The availability endpoints used to proxy
 * {@code DayAvailabilityDto} straight through: {@code startHour}, {@code startMinute},
 * {@code label}, {@code blockedSlots}. That is availability-service's internal shape, and
 * handing it to a third party had three separate consequences —
 *
 * <ul>
 *   <li>no {@code availabilityId}, so a reseller had <b>nothing to book against</b>. The
 *       booking endpoint requires one; the availability endpoint never produced one. The
 *       two halves of the supplier API did not meet.</li>
 *   <li>a reseller coupled to our internal model, so changing a field inside
 *       availability-service would silently break somebody else's integration;</li>
 *   <li>{@code blockedSlots} leaked when the venue is deliberately closed off —
 *       operational information a reseller has no business seeing.</li>
 * </ul>
 *
 * <p><b>A slot is not a bookable unit.</b> Whole-space hire is sold for a window of a
 * permitted length, so what a reseller can buy is a (start, duration) pair whose every
 * covered slot is free — not a bare 30-minute cell. Emitting cells would let a reseller
 * book 30 minutes of a venue that only sells two-hour blocks.
 */
public final class OctoAvailabilityPolicy {

    private OctoAvailabilityPolicy() {}

    /** SK Binge's grid resolution. Slots are half-hourly everywhere in the platform. */
    static final int SLOT_MINUTES = 30;

    /**
     * Ceiling on availability objects returned for one day.
     *
     * <p>Every (start × duration) combination is a legitimate product, but a venue open
     * 13 hours with nine permitted durations produces a few hundred — and risk DIST-R6 is
     * precisely that resellers poll this hard and wide. Bounded so one query cannot
     * become an expensive response, and the clamp is visible to the caller as a shorter
     * list rather than silently different data.
     */
    static final int MAX_OBJECTS_PER_DAY = 400;

    /** One bookable window, in the shape OCTO 1.0 expects. */
    public record Availability(String id,
                               LocalDateTime localDateTimeStart,
                               LocalDateTime localDateTimeEnd,
                               boolean available,
                               String status,
                               int vacancies) {}

    /**
     * The durations this event type may be booked for, in minutes.
     *
     * <p>{@code permittedDurations} is an explicit allow-list when the venue set one
     * (V84/B5). When it is empty the rule is the pre-V84 one: any 30-minute multiple
     * between {@code minHours} and {@code maxHours}. Deriving it here rather than
     * defaulting to a single length matters — a venue that sells 2, 3 and 4 hour blocks
     * would otherwise appear to sell only one of them.
     */
    public static List<Integer> bookableDurations(List<Integer> permittedDurations,
                                                  Integer minHours, Integer maxHours) {
        if (permittedDurations != null && !permittedDurations.isEmpty()) {
            return permittedDurations.stream()
                .filter(d -> d != null && d >= SLOT_MINUTES && d % SLOT_MINUTES == 0)
                .sorted()
                .toList();
        }
        int min = minHours == null ? 1 : Math.max(1, minHours);
        int max = maxHours == null ? min : Math.max(min, maxHours);
        List<Integer> derived = new ArrayList<>();
        for (int minutes = min * 60; minutes <= max * 60; minutes += SLOT_MINUTES) {
            derived.add(minutes);
        }
        return derived;
    }

    /**
     * Every window a reseller may buy on this day.
     *
     * @param freeStartMinutes minutes-from-midnight of each FREE 30-minute slot
     * @param durations        permitted booking lengths in minutes
     */
    public static List<Availability> bookableWindows(LocalDate date,
                                                     Set<Integer> freeStartMinutes,
                                                     List<Integer> durations) {
        if (date == null || freeStartMinutes == null || freeStartMinutes.isEmpty()
                || durations == null || durations.isEmpty()) {
            return List.of();
        }

        // Sorted so the response is stable between identical requests. A list that
        // reorders itself reads to a reseller as inventory that changed.
        Set<Integer> free = new TreeSet<>(freeStartMinutes);
        List<Availability> windows = new ArrayList<>();

        for (Integer start : free) {
            for (Integer duration : durations) {
                if (windows.size() >= MAX_OBJECTS_PER_DAY) return windows;
                if (!wholeWindowIsFree(free, start, duration)) continue;

                LocalDateTime from = LocalDateTime.of(date, minutesToTime(start));
                windows.add(new Availability(
                    AvailabilityIdCodec.encode(from, duration),
                    from,
                    from.plusMinutes(duration),
                    true,
                    "AVAILABLE",
                    // Whole-space exclusive hire: the space is either free or it is not.
                    // A vacancy count above one would imply the venue can be sold twice
                    // over for the same window.
                    1));
            }
        }
        return windows;
    }

    /**
     * Every 30-minute cell the window covers must be free.
     *
     * <p>Checking only the first cell is the obvious mistake and would sell a two-hour
     * booking that overlaps an existing one after its first half hour — an oversell
     * created by the supplier rather than caught by it.
     */
    private static boolean wholeWindowIsFree(Set<Integer> free, int startMinute, int durationMinutes) {
        for (int at = startMinute; at < startMinute + durationMinutes; at += SLOT_MINUTES) {
            if (!free.contains(at)) return false;
        }
        return true;
    }

    private static LocalTime minutesToTime(int minutesFromMidnight) {
        return LocalTime.of(minutesFromMidnight / 60, minutesFromMidnight % 60);
    }
}
