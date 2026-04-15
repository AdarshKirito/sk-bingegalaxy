package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitlistOfferExpiryScheduler {

    private final WaitlistService waitlistService;

    /** Runs every 5 minutes to expire waitlist offers that have timed out. */
    @Scheduled(fixedRate = 300_000) // 5 minutes
    @SchedulerLock(name = "waitlistOfferExpiry", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void expireStaleOffers() {
        int expired = waitlistService.expireStaleOffers();
        if (expired > 0) {
            log.info("Expired {} stale waitlist offers", expired);
        }
    }
}
