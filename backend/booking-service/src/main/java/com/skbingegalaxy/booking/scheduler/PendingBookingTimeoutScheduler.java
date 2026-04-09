package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saga timeout: auto-cancels PENDING bookings that remain unpaid
 * beyond a configurable threshold.
 *
 * This prevents reservation slots from being held indefinitely
 * when customers abandon the payment flow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingBookingTimeoutScheduler {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    /** Minutes after which an unpaid PENDING booking is auto-cancelled. */
    @Value("${app.saga.pending-timeout-minutes:30}")
    private int pendingTimeoutMinutes;

    /**
     * Runs every 5 minutes to check for stale PENDING bookings.
     */
    @Scheduled(fixedDelayString = "${app.saga.check-interval-ms:300000}")
    @SchedulerLock(name = "cancelStalePendingBookings", lockAtLeastFor = "4m", lockAtMostFor = "9m")
    public void cancelStalePendingBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(pendingTimeoutMinutes);
        List<Booking> stale = bookingRepository.findStalePendingBookings(cutoff);

        if (stale.isEmpty()) return;

        log.info("Saga timeout: found {} stale PENDING bookings (older than {} min)",
            stale.size(), pendingTimeoutMinutes);

        for (Booking booking : stale) {
            try {
                bookingService.cancelBookingForSystem(
                    booking.getBookingRef(),
                    "Booking auto-cancelled after payment timeout");
                log.info("Saga timeout: auto-cancelled stale booking {} (created {})",
                    booking.getBookingRef(), booking.getCreatedAt());
            } catch (Exception e) {
                log.error("Saga timeout: failed to cancel stale booking {}: {}",
                    booking.getBookingRef(), e.getMessage());
            }
        }
    }
}
