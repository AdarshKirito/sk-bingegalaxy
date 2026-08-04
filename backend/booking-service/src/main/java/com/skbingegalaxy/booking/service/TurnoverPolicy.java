package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.domain.OccupancyWindow;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.entity.SlotHold;
import com.skbingegalaxy.booking.repository.BingeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the setup / cleanup turnover buffers that widen a reservation's
 * {@link OccupancyWindow}, and snapshots them onto the reservation.
 *
 * <p><b>Resolution order</b> (narrower scope wins; NULL means "inherit"):
 * <ol>
 *   <li>{@link EventType#getSetupMinutes()} / {@link EventType#getCleanupMinutes()}</li>
 *   <li>{@link Binge#getDefaultSetupMinutes()} / {@link Binge#getDefaultCleanupMinutes()}</li>
 *   <li>zero</li>
 * </ol>
 * This mirrors the existing {@code Binge.openTime} idiom, where NULL on the
 * narrower scope falls back to the wider default.
 *
 * <p><b>Resolve once, then read the snapshot.</b> Buffers are resolved at
 * creation time and copied onto {@code Booking}/{@code SlotHold}. Everything
 * afterwards — conflict detection, capacity counting, the V81 database backstop
 * — reads the snapshot, never the live configuration. Reading live values would
 * mean that editing an event type retroactively changed what past bookings
 * occupied, and the application and the trigger would start disagreeing about
 * rows that were perfectly legal when they were written.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TurnoverPolicy {

    private final BingeRepository bingeRepository;

    /** Resolved buffers for a reservation about to be created. */
    public record Buffers(int setupMinutes, int cleanupMinutes) {
        public static final Buffers NONE = new Buffers(0, 0);

        public OccupancyWindow windowFor(int startMinute, int durationMinutes) {
            return OccupancyWindow.of(startMinute, durationMinutes, setupMinutes, cleanupMinutes);
        }

        public boolean isZero() {
            return setupMinutes == 0 && cleanupMinutes == 0;
        }
    }

    /**
     * Resolve the buffers that a new reservation for {@code eventType} at
     * {@code bingeId} should carry.
     *
     * <p>Fails soft: if the binge row cannot be loaded the event type's own
     * values still apply and the missing side resolves to zero. A transient
     * lookup problem must not silently widen or narrow occupancy in a way that
     * disagrees with what was already persisted elsewhere in the same flow.
     */
    public Buffers resolve(Long bingeId, EventType eventType) {
        Integer etSetup = eventType != null ? eventType.getSetupMinutes() : null;
        Integer etCleanup = eventType != null ? eventType.getCleanupMinutes() : null;

        if (etSetup != null && etCleanup != null) {
            return new Buffers(OccupancyWindow.clampBuffer(etSetup), OccupancyWindow.clampBuffer(etCleanup));
        }

        int bingeSetup = 0;
        int bingeCleanup = 0;
        if (bingeId != null) {
            Binge binge = bingeRepository.findById(bingeId).orElse(null);
            if (binge != null) {
                bingeSetup = binge.getDefaultSetupMinutes();
                bingeCleanup = binge.getDefaultCleanupMinutes();
            } else {
                log.warn("Turnover buffers: binge {} not found; falling back to zero venue defaults", bingeId);
            }
        }

        return new Buffers(
            OccupancyWindow.clampBuffer(etSetup != null ? etSetup : bingeSetup),
            OccupancyWindow.clampBuffer(etCleanup != null ? etCleanup : bingeCleanup));
    }

    // ── Reading the snapshot off persisted rows ──────────────────────────

    /**
     * Occupancy window of an existing booking, from its snapshot.
     *
     * @param effectiveDurationMinutes duration as the caller already computed it —
     *        early-checkout and COMPLETED rounding rules live in
     *        {@code BookingService#getEffectiveDurationMinutes} and must not be
     *        duplicated here
     */
    public static OccupancyWindow windowOf(Booking booking, int effectiveDurationMinutes) {
        int startMinute = booking.getStartTime().getHour() * 60 + booking.getStartTime().getMinute();
        return OccupancyWindow.of(startMinute, effectiveDurationMinutes,
            booking.getSetupMinutes(), booking.getCleanupMinutes());
    }

    /** Occupancy window of a live hold, from its snapshot. */
    public static OccupancyWindow windowOf(SlotHold hold) {
        int startMinute = hold.getStartTime().getHour() * 60 + hold.getStartTime().getMinute();
        return OccupancyWindow.of(startMinute, hold.getDurationMinutes(),
            hold.getSetupMinutes(), hold.getCleanupMinutes());
    }
}
