package com.skbingegalaxy.booking.domain;

/**
 * The span of minutes-since-midnight during which a reservation makes a physical
 * resource (a {@code VenueRoom}, or a room-less venue) unavailable.
 *
 * <p>This is deliberately <em>wider</em> than the billable booking interval. A
 * celebration space that has just hosted a party cannot host the next one at the
 * instant the first ends — it has to be reset, re-decorated and cleaned. Before
 * V81 the system compared raw {@code [start, start + duration)} intervals, so
 * 19:00–22:00 and 22:00–01:00 were both accepted in the same room with zero
 * reset time. Nothing rejected it; staff found out on the night.
 *
 * <pre>
 *          setup                billable                cleanup
 *      |&lt;--------&gt;|&lt;--------------------------&gt;|&lt;--------------&gt;|
 *      ^                        ^                                ^
 *   occupancy                 start                          occupancy
 *     start                                                     end
 * </pre>
 *
 * <p>Two reservations conflict when their occupancy windows overlap — never when
 * only their billable intervals do. Both sides of every comparison must be
 * widened; widening only the incoming request would let an existing booking's
 * cleanup time be sold away.
 *
 * <p><b>Half-open by design.</b> {@code [start, end)} — a window ending at 22:00
 * does not conflict with one starting at 22:00. That is what makes a zero-buffer
 * venue behave exactly as it did before V81.
 *
 * <p><b>Same-day only.</b> {@code startMinute} may go negative when a booking
 * starts within its own setup buffer of midnight, and {@code endMinute} may
 * exceed 1440. Overlap arithmetic handles both correctly, but callers compare
 * windows within a single {@code bookingDate}. That is safe here because
 * {@code Binge.closeTime} must be strictly greater than {@code openTime}, so no
 * booking can span midnight in the first place.
 *
 * <p>Immutable, dependency-free and side-effect-free, so the rule can be unit
 * tested without a Spring context or a database.
 *
 * @param startMinute inclusive start, minutes since midnight (may be negative)
 * @param endMinute   exclusive end, minutes since midnight (may exceed 1440)
 */
public record OccupancyWindow(int startMinute, int endMinute) {

    /** Widest buffer a venue may configure, mirrored by the V81 DB CHECK constraints. */
    public static final int MAX_BUFFER_MINUTES = 240;

    public OccupancyWindow {
        if (endMinute < startMinute) {
            throw new IllegalArgumentException(
                "Occupancy window cannot end before it starts: [" + startMinute + "," + endMinute + ")");
        }
    }

    /**
     * Build the occupancy window for a reservation.
     *
     * @param startMinute     billable start, minutes since midnight
     * @param durationMinutes billable duration; values below 1 are treated as 1 so a
     *                        malformed row still occupies something rather than
     *                        silently colliding with everything
     * @param setupMinutes    prep time reserved before the start (clamped to 0..240)
     * @param cleanupMinutes  turnover time reserved after the end (clamped to 0..240)
     */
    public static OccupancyWindow of(int startMinute, int durationMinutes,
                                     int setupMinutes, int cleanupMinutes) {
        int duration = Math.max(durationMinutes, 1);
        int setup = clampBuffer(setupMinutes);
        int cleanup = clampBuffer(cleanupMinutes);
        return new OccupancyWindow(startMinute - setup, startMinute + duration + cleanup);
    }

    /** The window a reservation with no configured buffers occupies — the pre-V81 behaviour. */
    public static OccupancyWindow ofBillableInterval(int startMinute, int durationMinutes) {
        return of(startMinute, durationMinutes, 0, 0);
    }

    /**
     * Clamp a buffer into the range the database will accept. Defensive rather than
     * validating: a caller that somehow supplies an out-of-range value should get
     * conservative occupancy, not a persistence failure at commit time. Genuine
     * validation of operator input happens at the admin write boundary.
     */
    public static int clampBuffer(Integer minutes) {
        if (minutes == null) return 0;
        if (minutes < 0) return 0;
        return Math.min(minutes, MAX_BUFFER_MINUTES);
    }

    /** True when this window and {@code other} share at least one minute. Half-open, so touching endpoints do not overlap. */
    public boolean overlaps(OccupancyWindow other) {
        return this.startMinute < other.endMinute && this.endMinute > other.startMinute;
    }

    /** Total minutes the resource is held, buffers included. */
    public int lengthMinutes() {
        return endMinute - startMinute;
    }
}
