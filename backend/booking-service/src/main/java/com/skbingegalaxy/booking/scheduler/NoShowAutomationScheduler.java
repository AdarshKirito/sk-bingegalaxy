package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BookingRepository;
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
import java.time.LocalTime;
import java.util.List;

/**
 * Marks active (CONFIRMED / PENDING) bookings as {@link BookingStatus#NO_SHOW}
 * once their start time has passed by more than {@code app.no-show.grace-minutes}
 * minutes and the customer never checked in.
 *
 * <p>This complements two pre-existing flows:
 * <ul>
 *   <li>The manual <b>end-of-day audit</b>
 *       ({@link com.skbingegalaxy.booking.service.BookingService#auditEndOfDay})
 *       which an admin runs once after 23:59 to close out the day. That flow
 *       also <i>advances the operational date</i>.</li>
 *   <li>This scheduler, which runs every 15 minutes and only marks
 *       NO_SHOW for the <i>current</i> operational day, never advancing the
 *       date. It exists so a 19:00 booking that no-shows is reflected in the
 *       UI by ~19:30, not the next morning.</li>
 * </ul>
 *
 * <p>Cluster-safe via ShedLock; the scheduler is idempotent: the next run
 * simply finds an empty candidate list because rows are now NO_SHOW.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoShowAutomationScheduler {

    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;

    @Value("${app.no-show.grace-minutes:30}")
    private int graceMinutes;

    @Value("${app.no-show.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.no-show.check-interval-ms:900000}") // 15 min
    @SchedulerLock(name = "NoShowAutomationScheduler", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void sweep() {
        if (!enabled) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalTime cutoff = now.toLocalTime().minusMinutes(graceMinutes);
        // Skip if the cutoff would wrap before midnight (i.e. early in the day)
        // — there's nothing to sweep when grace > minutes-since-midnight.
        if (now.toLocalTime().isBefore(LocalTime.of(0, 0).plusMinutes(graceMinutes))) {
            return;
        }

        List<Booking> candidates = bookingRepository.findNoShowCandidates(today, cutoff);
        if (candidates.isEmpty()) return;

        int marked = 0;
        for (Booking b : candidates) {
            try {
                if (b.isCheckedIn() || b.getStatus() == BookingStatus.CHECKED_IN) {
                    continue; // race: customer just checked in
                }
                stateMachine.transition(
                    b, BookingTransitionEvent.MARK_NO_SHOW,
                    TransitionActor.system(),
                    "No check-in within " + graceMinutes + " min of start time");
                marked++;
            } catch (Exception e) {
                log.warn("Failed to auto-NO_SHOW booking {}: {}", b.getBookingRef(), e.getMessage());
            }
        }
        if (marked > 0) {
            log.info("Auto-no-show sweep marked {} booking(s) (grace={}min, cutoff={})",
                marked, graceMinutes, cutoff);
        }
    }
}
