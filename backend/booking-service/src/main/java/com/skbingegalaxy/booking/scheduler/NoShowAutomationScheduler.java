package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.VenueClockService;
import com.skbingegalaxy.booking.service.statemachine.BookingStateMachine;
import com.skbingegalaxy.booking.service.statemachine.BookingTransitionEvent;
import com.skbingegalaxy.booking.service.statemachine.TransitionActor;
import com.skbingegalaxy.common.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Marks active (CONFIRMED / PENDING) bookings as {@link BookingStatus#NO_SHOW}
 * once the reservation has passed its <b>midpoint</b> — i.e. {@code startTime +
 * duration/2} in the <i>venue's</i> local time — and the customer never checked
 * in. A 18:00–20:00 booking therefore only no-shows after 19:00, never at a
 * fixed "start + 30 min" offset, so the grace scales with how long the
 * reservation actually is.
 *
 * <p>This complements two pre-existing flows:
 * <ul>
 *   <li>The manual <b>end-of-day audit</b>
 *       ({@link com.skbingegalaxy.booking.service.BookingService#auditEndOfDay})
 *       which an admin runs once after 23:59 to close out the day. That flow
 *       also <i>advances the operational date</i>.</li>
 *   <li>This scheduler, which runs every 15 minutes and reflects no-shows in
 *       the UI shortly after each reservation's midpoint, never advancing the
 *       date.</li>
 * </ul>
 *
 * <p><b>Timezone contract:</b> {@code bookingDate}/{@code startTime} are
 * venue-local. The candidate set is bounded by a ±1 day window around the UTC
 * date (covers every venue offset), then the precise midpoint comparison is
 * done against {@link VenueClockService} — never {@code LocalDateTime.now(UTC)}
 * directly, which would skew the cutoff for any non-UTC venue.
 *
 * <p>Cluster-safe via ShedLock; idempotent: the next run simply finds fewer
 * candidates because rows are now NO_SHOW.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoShowAutomationScheduler {

    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final VenueClockService venueClock;

    /**
     * Optional safety floor (minutes after start) below which a no-show is never
     * raised, even if the midpoint is sooner. Defaults to 0 → pure midpoint.
     */
    @Value("${app.no-show.min-grace-minutes:0}")
    private int minGraceMinutes;

    @Value("${app.no-show.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.no-show.check-interval-ms:900000}") // 15 min
    @SchedulerLock(name = "NoShowAutomationScheduler", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void sweep() {
        if (!enabled) return;

        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        // ±1 day brackets every venue timezone; the exact cutoff is venue-local below.
        List<Booking> candidates = bookingRepository.findNoShowSweepCandidates(
            todayUtc.minusDays(1), todayUtc.plusDays(1));
        if (candidates.isEmpty()) return;

        int marked = 0;
        for (Booking b : candidates) {
            try {
                if (b.isCheckedIn() || b.getStatus() == BookingStatus.CHECKED_IN) {
                    continue; // race: customer just checked in
                }
                ZoneId venueZone = venueClock.zoneOf(b.getBingeId());
                LocalDateTime venueNow = LocalDateTime.now(venueZone);
                LocalDateTime noShowAt = noShowThreshold(b);
                if (venueNow.isBefore(noShowAt)) {
                    continue; // not yet past the reservation midpoint
                }
                stateMachine.transition(
                    b, BookingTransitionEvent.MARK_NO_SHOW,
                    TransitionActor.system(),
                    "No check-in by reservation midpoint (" + noShowAt.toLocalTime() + ")");
                marked++;
            } catch (Exception e) {
                log.warn("Failed to auto-NO_SHOW booking {}: {}", b.getBookingRef(), e.getMessage());
            }
        }
        if (marked > 0) {
            log.info("Auto-no-show sweep marked {} booking(s) past their reservation midpoint", marked);
        }
    }

    /** Venue-local instant at/after which the booking is a no-show: start + max(duration/2, minGrace). */
    private LocalDateTime noShowThreshold(Booking b) {
        int durMin = effectiveDurationMinutes(b);
        int offset = Math.max(durMin / 2, minGraceMinutes);
        // Never zero — guard against malformed zero-duration rows so we still
        // require *some* time to elapse past the scheduled start.
        offset = Math.max(offset, 1);
        return LocalDateTime.of(b.getBookingDate(), b.getStartTime()).plusMinutes(offset);
    }

    private static int effectiveDurationMinutes(Booking b) {
        if (b.getDurationMinutes() != null && b.getDurationMinutes() > 0) {
            return b.getDurationMinutes();
        }
        return Math.max(b.getDurationHours(), 0) * 60;
    }
}
