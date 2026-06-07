package com.skbingegalaxy.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Per-minute email send rate limiter backed by a Semaphore.
 *
 * Protects SMTP from bulk-cancel storms (e.g. admin cancels 500 bookings at once,
 * each emitting a BOOKING_CANCELLED Kafka event consumed simultaneously by the
 * notification service). Without this guard, all 500 emails hit JavaMail in seconds,
 * exhausting SMTP connection pool and rate-limit quotas at the provider level.
 *
 * Strategy: Semaphore pre-loaded with N permits; Kafka consumer threads must acquire
 * one permit per email. A @Scheduled task refills the semaphore every 60 seconds.
 * Threads that cannot acquire within MAX_WAIT_MS back off and log a warning — the
 * Kafka offset is still committed, so the notification is not duplicated, but it IS
 * dropped. The dedup window (DEDUP_TTL_HOURS=1) in EventListener means a retry from
 * a DLT replay would resend after that window expires.
 */
@Component
@Slf4j
public class EmailRateLimiter {

    /** Hard limit on email sends per minute. Configurable via app.notification.email-rate-limit-per-minute. */
    private final int limitPerMinute;

    /**
     * Fair semaphore: threads that block longer get priority when permits become available,
     * preventing starvation under sustained load across 3 Kafka consumer threads.
     */
    private final Semaphore semaphore;

    public EmailRateLimiter(
            @Value("${app.notification.email-rate-limit-per-minute:100}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
        this.semaphore = new Semaphore(limitPerMinute, true);
        log.info("Email rate limiter initialized: {} emails/minute", limitPerMinute);
    }

    /**
     * Refill permits every 60 seconds. Drains leftover (unused) permits first so we
     * don't accumulate more than limitPerMinute across windows (no burst carry-over).
     */
    @Scheduled(fixedDelay = 60_000)
    public void refillPermits() {
        int unused = semaphore.drainPermits();
        semaphore.release(limitPerMinute);
        if (unused < limitPerMinute) {
            log.info("Email rate window reset — used {}/{} permits last minute",
                limitPerMinute - unused, limitPerMinute);
        }
    }

    /**
     * Try to acquire one email send slot, blocking up to {@code maxWaitMs}.
     *
     * @return true if a slot was acquired; false if timed out (caller should
     *         drop the send and log a warning — the Kafka offset is committed,
     *         notification is skipped for this event).
     */
    public boolean tryAcquire(long maxWaitMs) {
        try {
            boolean acquired = semaphore.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("Email rate limit ({}/min) exceeded — notification dropped after {}ms wait. "
                    + "Check EMAIL_RATE_PER_MINUTE env var or investigate bulk-cancel storm.",
                    limitPerMinute, maxWaitMs);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Email rate limiter interrupted — dropping notification");
            return false;
        }
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
