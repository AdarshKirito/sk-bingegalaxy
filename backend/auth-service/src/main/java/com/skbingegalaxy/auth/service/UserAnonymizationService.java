package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.auth.repository.UserSessionRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * DPDP / GDPR right-to-erasure implementation.
 *
 * <p>PII is never hard-deleted because booking and payment records reference
 * {@code user_id} and must be retained for financial/legal purposes (7 years
 * for Indian GST). Instead, PII fields are replaced with anonymous placeholders.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #requestDeletion} — user submits erasure request; account is soft-deleted
 *       (login blocked), retention window starts (default 30 days).</li>
 *   <li>{@link #anonymizePendingDeletions} (daily scheduler) — after the retention window,
 *       replaces all PII with anonymized values. Each user is committed independently
 *       so one failure never blocks the others.</li>
 *   <li>{@link #anonymizeUser} — ADMIN trigger for immediate anonymization.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAnonymizationService {

    /** Days after deletion_requested_at before the account is anonymized automatically. */
    @Value("${app.privacy.data-retention-days:30}")
    private int dataRetentionDays;

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;

    /**
     * Submit an erasure request: soft-deletes the account and starts the retention clock.
     * Returns without error if the user is already pending deletion.
     */
    @Transactional
    public void requestDeletion(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getAnonymizedAt() != null) {
            throw new BusinessException("Account has already been anonymized", HttpStatus.GONE);
        }
        if (user.getDeletionRequestedAt() != null) {
            return; // idempotent — already requested
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setDeletionRequestedAt(now);
        user.setDeletedAt(now);
        user.setDataRetentionExpiresAt(now.plusDays(dataRetentionDays));
        userRepository.save(user);

        // Revoke all active sessions immediately so the user cannot log in
        sessionRepository.revokeAllForUser(userId, null, "Account deletion requested by user");

        log.info("Erasure request submitted for user {} — soft-deleted, anonymization after {} days ({})",
            userId, dataRetentionDays, user.getDataRetentionExpiresAt());
    }

    /**
     * Immediately anonymize one user (ADMIN action).
     */
    @Transactional
    public void anonymizeUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.getAnonymizedAt() != null) {
            return; // already done — idempotent
        }
        doAnonymize(user);
        log.info("User {} anonymized on-demand by admin", userId);
    }

    /**
     * Daily scheduler: anonymize all accounts whose retention window has expired.
     *
     * NOT @Transactional at the job level — each user is committed independently
     * via the @Transactional save() calls inside doAnonymize(), so one failure
     * does not roll back other successful anonymizations.
     */
    @Scheduled(cron = "${app.privacy.anonymization-cron:0 30 2 * * *}",
               zone  = "${app.privacy.anonymization-zone:Asia/Kolkata}")
    @SchedulerLock(name = "userAnonymization", lockAtLeastFor = "1m", lockAtMostFor = "15m")
    public void anonymizePendingDeletions() {
        List<User> due = userRepository.findPendingAnonymization(LocalDateTime.now(ZoneOffset.UTC));
        if (due.isEmpty()) {
            log.info("User anonymization job: no accounts due for anonymization");
            return;
        }
        log.info("User anonymization job: processing {} account(s)", due.size());
        int succeeded = 0;
        for (User user : due) {
            try {
                // Each doAnonymize call has its own transaction via userRepository.save()
                doAnonymize(user);
                succeeded++;
            } catch (Exception e) {
                log.error("Failed to anonymize user {}: {}", user.getId(), e.getMessage(), e);
            }
        }
        log.info("User anonymization job complete: {}/{} users anonymized", succeeded, due.size());
    }

    // Called via this.doAnonymize() — Spring AOP does not intercept self-invocation,
    // so @Transactional here would be ineffective. Each userRepository.save() creates
    // its own transaction automatically, which is the desired per-user isolation.
    private void doAnonymize(User user) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String placeholder = "deleted-" + user.getId();

        user.setFirstName("Deleted");
        user.setLastName("User");
        user.setEmail(placeholder + "@anonymized.invalid");
        user.setPhone(null);
        user.setPhoneCountryCode(null);
        user.setAddressLine1(null);
        user.setAddressLine2(null);
        user.setCity(null);
        user.setState(null);
        user.setPostalCode(null);
        user.setCountry(null);
        user.setMfaSecret(null);
        user.setMfaRecoveryCodesHash(null);
        user.setDeletedAt(user.getDeletedAt() != null ? user.getDeletedAt() : now);
        user.setAnonymizedAt(now);
        userRepository.save(user);

        log.info("User {} anonymized — PII replaced, anonymizedAt={}", user.getId(), now);
    }
}
