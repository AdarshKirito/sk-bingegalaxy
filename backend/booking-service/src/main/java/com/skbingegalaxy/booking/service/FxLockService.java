package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.FxLockResponse;
import com.skbingegalaxy.booking.entity.FxRateLock;
import com.skbingegalaxy.booking.repository.FxRateLockRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.common.money.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Locks an FX rate for a fixed window so that the customer's quoted total
 * does not change between checkout preview and gateway charge.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #lockFx} creates a row with status ACTIVE.</li>
 *   <li>{@link #consumeLock} marks it CONSUMED. A double-consume attempt is
 *       rejected with {@link BusinessException}.</li>
 *   <li>{@link #expireStaleLocks} runs every minute and flips ACTIVE rows
 *       whose {@code locked_until} has passed to EXPIRED.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FxLockService {

    public static final int DEFAULT_TTL_MINUTES = 15;

    private final FxRateLockRepository repo;
    private final CurrencyService currencyService;

    public FxLockResponse lockFx(String fromCurrency, String toCurrency,
                                 BigDecimal baseAmount, Integer ttlMinutes,
                                 Long customerId, String bookingRef) {
        if (fromCurrency == null || fromCurrency.isBlank()) {
            throw new BusinessException("fromCurrency is required");
        }
        if (toCurrency == null || toCurrency.isBlank()) {
            throw new BusinessException("toCurrency is required");
        }
        int ttl = (ttlMinutes == null || ttlMinutes <= 0) ? DEFAULT_TTL_MINUTES : Math.min(ttlMinutes, 60);
        BigDecimal rate = currencyService.getRate(toCurrency.toUpperCase());

        BigDecimal converted = baseAmount == null
            ? null
            : MoneyUtil.convertWithRate(baseAmount, rate, toCurrency);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FxRateLock lock = FxRateLock.builder()
            .lockToken(UUID.randomUUID().toString())
            .customerId(customerId)
            .bookingRef(bookingRef)
            .fromCurrency(fromCurrency.toUpperCase())
            .toCurrency(toCurrency.toUpperCase())
            .fxRate(rate)
            .fxSource("MANUAL")
            .baseAmount(baseAmount)
            .convertedAmount(converted)
            .lockedUntil(now.plusMinutes(ttl))
            .status(FxRateLock.Status.ACTIVE)
            .build();
        lock = repo.save(lock);
        log.info("FX lock created token={} {}->{} rate={} ttl={}min",
            lock.getLockToken(), lock.getFromCurrency(), lock.getToCurrency(), lock.getFxRate(), ttl);
        return toDto(lock);
    }

    /** Consume an active lock with no ownership check (legacy/INR callers). */
    public FxRateLock consumeLock(String lockToken) {
        return consumeLock(lockToken, null);
    }

    /**
     * Consume an active lock; returns the row. Throws if missing/expired/already consumed.
     *
     * <p>When both the lock and {@code expectedCustomerId} carry a customer id, they must
     * match — defense-in-depth so a lock bound to one account can't be replayed against
     * another's booking. Null-tolerant on either side: legacy/anonymous locks (no
     * customerId) and callers that don't supply one are not ownership-checked, so this
     * never breaks the domestic-INR or admin booking paths.
     */
    public FxRateLock consumeLock(String lockToken, Long expectedCustomerId) {
        if (lockToken == null || lockToken.isBlank()) {
            throw new BusinessException("FX lock token is required");
        }
        FxRateLock lock = repo.findByLockToken(lockToken)
            .orElseThrow(() -> new ResourceNotFoundException("FxRateLock", "token", lockToken));
        if (lock.getCustomerId() != null && expectedCustomerId != null
                && !lock.getCustomerId().equals(expectedCustomerId)) {
            log.warn("FX lock ownership mismatch token={} lockOwner={} attemptedBy={}",
                lockToken, lock.getCustomerId(), expectedCustomerId);
            throw new BusinessException("This FX rate lock belongs to a different account",
                org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (lock.getStatus() == FxRateLock.Status.CONSUMED) {
            throw new BusinessException("FX lock already consumed");
        }
        if (lock.getStatus() == FxRateLock.Status.EXPIRED || lock.getLockedUntil().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            lock.setStatus(FxRateLock.Status.EXPIRED);
            repo.save(lock);
            throw new BusinessException("FX lock has expired — please refresh checkout");
        }
        lock.setStatus(FxRateLock.Status.CONSUMED);
        lock.setConsumedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repo.save(lock);
    }

    @Transactional(readOnly = true)
    public FxRateLock findActive(String lockToken) {
        return repo.findByLockTokenAndStatus(lockToken, FxRateLock.Status.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("FxRateLock", "token", lockToken));
    }

    /** Expires stale ACTIVE locks every minute. */
    @Scheduled(fixedDelay = 60_000)
    public void expireStaleLocks() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC);
        List<FxRateLock> stale = repo.findByStatusAndLockedUntilBefore(FxRateLock.Status.ACTIVE, cutoff);
        if (stale.isEmpty()) return;
        stale.forEach(l -> l.setStatus(FxRateLock.Status.EXPIRED));
        repo.saveAll(stale);
        log.info("Expired {} stale FX locks", stale.size());
    }

    public FxLockResponse toDto(FxRateLock l) {
        return FxLockResponse.builder()
            .lockToken(l.getLockToken())
            .fromCurrency(l.getFromCurrency())
            .toCurrency(l.getToCurrency())
            .fxRate(l.getFxRate())
            .fxSource(l.getFxSource())
            .baseAmount(l.getBaseAmount())
            .convertedAmount(l.getConvertedAmount())
            .lockedAt(l.getLockedAt())
            .lockedUntil(l.getLockedUntil())
            .status(l.getStatus().name())
            .build();
    }
}
