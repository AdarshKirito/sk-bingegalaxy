package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.service.SlotHoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases pre-payment slot holds whose TTL has elapsed and prunes very old
 * terminal rows. Runs every minute (ShedLock-guarded so only one
 * booking-service replica processes the batch).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlotHoldExpiryScheduler {

    private final SlotHoldService slotHoldService;

    @Scheduled(fixedDelayString = "${app.slot-hold.expiry-check-interval-ms:60000}")
    @SchedulerLock(name = "slotHoldExpiry", lockAtMostFor = "55s", lockAtLeastFor = "5s")
    public void expireStaleHolds() {
        try {
            int n = slotHoldService.expireStaleHolds();
            if (n > 0) {
                log.debug("SlotHoldExpiryScheduler: expired {} holds", n);
            }
        } catch (Exception ex) {
            log.error("SlotHoldExpiryScheduler: expire failed: {}", ex.getMessage(), ex);
        }
    }

    /** Daily cleanup of old terminal rows so the table stays small. */
    @Scheduled(cron = "${app.slot-hold.purge-cron:0 15 3 * * *}")
    @SchedulerLock(name = "slotHoldPurge", lockAtMostFor = "10m", lockAtLeastFor = "30s")
    public void purgeOldTerminalHolds() {
        try {
            slotHoldService.purgeOldTerminalHolds();
        } catch (Exception ex) {
            log.error("SlotHoldExpiryScheduler: purge failed: {}", ex.getMessage(), ex);
        }
    }
}
