package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Daily maintenance task that physically removes used / expired password-reset
 * tokens. The {@code password_reset_token} table grows with every
 * forgot-password request; without a purge it will slowly bloat (large
 * B-tree, slower inserts, higher autovacuum cost).
 *
 * <p>Retention: 7 days past expiry. Used tokens that are older than their
 * {@code expiresAt} are eligible. A 7-day grace gives security auditors a
 * short window to inspect recent reset activity before rows disappear.</p>
 *
 * <p>Protected by ShedLock so only one pod runs the purge cluster-wide.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetTokenCleanupService {

    private static final int RETENTION_DAYS = 7;

    private final PasswordResetTokenRepository resetTokenRepository;

    @Scheduled(cron = "${app.auth.reset-token-cleanup-cron:0 15 2 * * *}")
    @SchedulerLock(name = "purgeExpiredResetTokens", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeExpiredResetTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = resetTokenRepository.deleteAllByExpiresAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} password-reset tokens older than {}", deleted, cutoff);
        }
    }
}
