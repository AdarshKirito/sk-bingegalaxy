package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.service.BingeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps approved-but-empty binges hourly. After a SUPER_ADMIN approves a
 * binge, the requesting ADMIN has 24 hours to create at least one active
 * event type. At the half-way mark (12 hours remaining) we deliver a
 * courtesy warning. At the deadline we auto-deactivate the binge and
 * notify both the admin and the super-admin pool.
 *
 * <p>ShedLock keeps this single-runner across replicas; without it every
 * pod would emit duplicate notifications and double-deactivate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BingeGracePeriodScheduler {

    private final BingeService bingeService;

    /**
     * Runs every 30 minutes — fine-grained enough to keep the warning + the
     * deactivation close to their nominal hour, cheap enough that it never
     * becomes a hot path.
     */
    @Scheduled(fixedRate = 30 * 60_000L) // 30 minutes
    @SchedulerLock(name = "bingeGracePeriodEnforcement", lockAtMostFor = "9m", lockAtLeastFor = "4m")
    public void sweep() {
        int deactivated = bingeService.enforceGracePeriod();
        if (deactivated > 0) {
            log.warn("Binge grace-period sweep auto-deactivated {} approved binge(s) with no events",
                deactivated);
        }
    }
}
