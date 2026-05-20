package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.service.BookingTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires PENDING booking-transfer offers whose {@code expiresAt} has passed.
 * Runs every 5 minutes; ShedLock keeps the work single-leader across replicas.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingTransferExpiryScheduler {

    private final BookingTransferService transferService;

    @Scheduled(fixedRate = 300_000) // 5 minutes
    @SchedulerLock(name = "bookingTransferExpiry", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void expireStalePending() {
        int expired = transferService.expireStalePending();
        if (expired > 0) {
            log.info("Expired {} pending booking transfers", expired);
        }
    }
}
