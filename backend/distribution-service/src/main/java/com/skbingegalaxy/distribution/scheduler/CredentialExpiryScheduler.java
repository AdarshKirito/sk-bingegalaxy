package com.skbingegalaxy.distribution.scheduler;

import com.skbingegalaxy.distribution.service.CredentialWatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the credential sweep once per hour across the cluster.
 *
 * <p><b>{@code @SchedulerLock} is not optional here.</b> Without it every replica runs
 * every sweep, producing one warning per replica for a single expiring credential. An
 * alert channel that repeats itself is one people stop reading, which leaves them worse
 * off than no alert at all.
 *
 * <p>The scheduler holds no logic — it decides only <em>when</em>. The rules live in
 * {@link CredentialWatchService}, where they can be tested without waiting an hour.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CredentialExpiryScheduler {

    private final CredentialWatchService credentialWatchService;

    /**
     * Hourly. A credential expiry is known days in advance, so a tighter cadence buys
     * nothing; an hour is frequent enough that a newly-unresolvable secret is noticed
     * long before a venue reports missing bookings.
     *
     * <p>{@code lockAtLeastFor} exceeds the expected run time so a fast sweep cannot be
     * picked up a second time by another replica whose clock is slightly ahead.
     */
    @Scheduled(fixedRate = 60 * 60_000L)
    @SchedulerLock(name = "distributionCredentialSweep",
                   lockAtMostFor = "10m", lockAtLeastFor = "5m")
    public void sweep() {
        CredentialWatchService.Findings findings = credentialWatchService.sweep();
        if (findings.isEmpty()) {
            log.debug("Credential sweep: nothing expiring or unusable");
        }
    }
}
