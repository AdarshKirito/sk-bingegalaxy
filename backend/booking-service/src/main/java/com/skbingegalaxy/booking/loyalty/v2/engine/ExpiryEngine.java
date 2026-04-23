package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsLot;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsLotRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Loyalty v2 — nightly points-expiry job.
 *
 * <p>Scans {@link LoyaltyPointsLot} rows where {@code remaining_points > 0}
 * and {@code expires_at <= now()}, then delegates to
 * {@link PointsWalletService#expireLot} which atomically zeroes the lot,
 * debits the wallet, and writes an EXPIRE ledger entry.
 *
 * <p>Runs at 02:15 UTC — staggered after {@code LoyaltyService} v1's
 * 02:00 run so dual-write reconciliation sees a stable picture.
 *
 * <p>Uses {@link SchedulerLock} (ShedLock) to guarantee single-node
 * execution in multi-replica deployments.
 *
 * <p><b>Not</b> triggered by the v1 ExpiryEngine — they operate on
 * independent tables.  When we cut over in M12, v1's scheduled job is
 * disabled and this one becomes authoritative.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExpiryEngine {

    private final LoyaltyPointsLotRepository lotRepository;
    private final LoyaltyPointsWalletRepository walletRepository;
    private final PointsWalletService walletService;
    private final TierEngine tierEngine;

    @Scheduled(cron = "0 15 2 * * *")     // 02:15 UTC every day
    @SchedulerLock(name = "loyaltyV2ExpiryEngine", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyExpiry() {
        expireAsOf(LocalDateTime.now());
    }

    /**
     * Public entry point — also callable from admin tooling for a one-off
     * catch-up (e.g. after a backfill).
     */
    public int expireAsOf(LocalDateTime cutoff) {
        List<LoyaltyPointsLot> due = lotRepository.findExpiringLots(cutoff);
        if (due.isEmpty()) {
            log.debug("[loyalty-v2] expiry engine: nothing due at {}", cutoff);
            return 0;
        }
        log.info("[loyalty-v2] expiry engine: {} lot(s) due at {}", due.size(), cutoff);

        int expiredCount = 0;
        for (LoyaltyPointsLot lot : due) {
            try {
                expireOneLot(lot.getId(), cutoff);
                expiredCount++;
            } catch (Exception ex) {
                // Keep going — one bad row shouldn't cripple the nightly run.
                log.error("[loyalty-v2] expiry failed for lot {}: {}", lot.getId(), ex.getMessage(), ex);
            }
        }
        log.info("[loyalty-v2] expiry engine finished: {} expired / {} due", expiredCount, due.size());
        return expiredCount;
    }

    @Transactional
    protected void expireOneLot(Long lotId, LocalDateTime at) {
        LoyaltyPointsLot lot = lotRepository.findById(lotId).orElse(null);
        if (lot == null || lot.getRemainingPoints() <= 0) return;

        Long membershipId = walletRepository.findById(lot.getWalletId())
                .map(w -> w.getMembershipId())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet " + lot.getWalletId() + " missing — cannot expire lot " + lotId));

        walletService.expireLot(membershipId, lotId, at);

        // Tier recalc (harmless if QC counters unchanged).  EXPIRE never
        // touches qualification events directly — but if we later add
        // "QC expires when its matching point lot expires" semantics, this
        // is the hook point.
        tierEngine.recalculateTier(membershipId, at);
    }
}
