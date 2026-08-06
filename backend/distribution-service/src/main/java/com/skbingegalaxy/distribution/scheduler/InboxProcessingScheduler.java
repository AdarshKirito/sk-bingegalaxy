package com.skbingegalaxy.distribution.scheduler;

import com.skbingegalaxy.distribution.service.InboxProcessor;
import com.skbingegalaxy.distribution.service.InboxRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the reservation inbox into canonical bookings.
 *
 * <p>Every 30 seconds, because the gap between a reseller confirming and the venue
 * seeing the booking is time in which the venue can sell the same slot to a walk-in.
 *
 * <p>{@code @SchedulerLock} is load-bearing here, not decoration. Two replicas draining
 * the same entry would both call booking-service. That endpoint is idempotent on
 * (externalSource, externalRef), so a duplicate BOOKING still cannot be created — but
 * the second call answers 200 "already recorded", and without the lock the inbox row
 * could be marked APPLIED twice and produce two settlements for one sale. The lock
 * prevents the race; idempotency is what survives it if the lock ever fails.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxProcessingScheduler {

    private final InboxProcessor inboxProcessor;
    private final InboxRetentionService inboxRetentionService;

    @Scheduled(fixedRate = 30_000L)
    @SchedulerLock(name = "distributionInboxDrain", lockAtMostFor = "5m", lockAtLeastFor = "20s")
    public void drain() {
        int applied = inboxProcessor.processOutstanding();
        if (applied > 0) {
            log.info("Inbox drain applied {} reservation(s)", applied);
        }
    }

    /**
     * Redacts traveller details from messages that can no longer be acted on.
     *
     * <p>Daily rather than per-drain: this is a retention obligation measured in weeks,
     * and running it beside the 30-second drain would spend a table scan every half
     * minute to find nothing. Offset from the drain so the two never contend for the
     * same rows.
     */
    @Scheduled(fixedRate = 24 * 60 * 60_000L, initialDelay = 5 * 60_000L)
    @SchedulerLock(name = "distributionInboxRetention", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void redactExpiredPayloads() {
        int redacted = inboxRetentionService.redactExpiredPayloads();
        if (redacted > 0) {
            log.info("Inbox retention redacted {} payload(s)", redacted);
        }
    }
}
