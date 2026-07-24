package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Persists MFA brute-force counters in their OWN transaction.
 *
 * <p>WHY a separate bean: a failed 2FA attempt ends with the caller throwing
 * (login rejects the code), and that exception rolls back the surrounding
 * transaction. If the counter were incremented in that same transaction it would
 * be rolled back too — the attempt count would never rise, and the throttle would
 * silently never engage no matter how many codes an attacker tried.
 *
 * <p>{@link Propagation#REQUIRES_NEW} makes each increment commit independently of
 * the caller's outcome. It also has to be a distinct bean rather than a private
 * method, because Spring's proxy-based transactions do not apply to self-invocation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfaThrottleService {

    private final UserRepository userRepository;

    @Value("${app.totp.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${app.totp.lock-minutes:15}")
    private int lockMinutes;

    /**
     * Record one failed verification, locking the account's MFA once the cap is hit.
     * Re-reads the user inside the new transaction so it never writes a stale copy
     * of the entity loaded by the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailure(Long userId) {
        userRepository.findById(userId).ifPresent(fresh -> {
            int attempts = fresh.getMfaFailedAttempts() + 1;
            if (attempts >= maxFailedAttempts) {
                fresh.setMfaLockedUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(lockMinutes));
                fresh.setMfaFailedAttempts(0);   // restart counting after the window
                log.warn("MFA locked for userId={} after {} failed attempts", userId, attempts);
            } else {
                fresh.setMfaFailedAttempts(attempts);
            }
            userRepository.save(fresh);
        });
    }

    /** Clear counters after a successful verification. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearFailures(Long userId) {
        userRepository.findById(userId).ifPresent(fresh -> {
            if (fresh.getMfaFailedAttempts() != 0 || fresh.getMfaLockedUntil() != null) {
                fresh.setMfaFailedAttempts(0);
                fresh.setMfaLockedUntil(null);
                userRepository.save(fresh);
            }
        });
    }

    /** True while the account is inside an MFA lockout window. */
    public boolean isThrottled(User user) {
        LocalDateTime until = user.getMfaLockedUntil();
        return until != null && LocalDateTime.now(ZoneOffset.UTC).isBefore(until);
    }

    /** Whole minutes remaining on the lock, at least 1. */
    public long minutesRemaining(User user) {
        LocalDateTime until = user.getMfaLockedUntil();
        if (until == null) return 0;
        long seconds = java.time.Duration.between(LocalDateTime.now(ZoneOffset.UTC), until).getSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }
}
