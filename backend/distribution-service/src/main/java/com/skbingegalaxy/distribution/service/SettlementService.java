package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.entity.SettlementRecord;
import com.skbingegalaxy.distribution.repository.SettlementRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a channel owes this venue, and whether it has arrived (slice 6, design G-E).
 *
 * <p><b>A settlement is a RECEIVABLE, not a payment.</b> Nothing here creates a Razorpay
 * or Stripe intent: payment-service stays the authority for SK-controlled money only.
 * Pushing channel money through the existing payment flow would corrupt reconciliation,
 * the ledger and refund logic simultaneously — and the money is not ours to move. Viator
 * is merchant of record and pays the net rate only <em>after</em> the experience
 * completes, so a channel booking is routinely weeks from cash.
 *
 * <p><b>Why totals are per currency, never summed.</b> Under native per-binge pricing a
 * destination may remit in one currency while the venue banks in another. Adding those
 * together produces a confident, meaningless number, so this returns a map keyed by
 * currency and refuses to collapse it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementRecordRepository settlementRepository;

    /** Statuses that mean money is still owed rather than received. */
    private static final List<SettlementRecord.SettlementStatus> OUTSTANDING = List.of(
        SettlementRecord.SettlementStatus.PENDING,
        SettlementRecord.SettlementStatus.EXPECTED,
        SettlementRecord.SettlementStatus.SHORT_PAID,
        SettlementRecord.SettlementStatus.DISPUTED);

    public List<SettlementRecord> outstandingForBinge(Long bingeId) {
        return settlementRepository.findByBingeIdAndSettlementStatusIn(bingeId, OUTSTANDING);
    }

    /**
     * Outstanding minor units per settlement currency.
     *
     * <p>A {@link TreeMap} so the console renders currencies in a stable order rather
     * than whatever the hash gave it today — a total that reorders between refreshes
     * reads as a total that changed.
     */
    public Map<String, Long> outstandingByCurrency(Long bingeId) {
        Map<String, Long> totals = new TreeMap<>();
        for (SettlementRecord r : outstandingForBinge(bingeId)) {
            totals.merge(r.getSettlementCurrency(), r.getOutstandingMinor(), Long::sum);
        }
        return totals;
    }

    /**
     * Record what a destination actually paid, and surface any difference.
     *
     * <p><b>Variance is stored, never silently absorbed.</b> A destination paying less
     * than expected is the single most important thing this table can tell a venue, and
     * quietly marking it PAID would hide exactly the money that needs chasing. The
     * status follows the arithmetic rather than the operator's intent.
     */
    @Transactional
    public SettlementRecord recordPayout(Long bingeId, Long settlementId,
                                         long actualMinor, LocalDate paidOn) {
        SettlementRecord record = settlementRepository.findById(settlementId)
            .filter(r -> r.getBingeId().equals(bingeId))
            .orElseThrow(() -> new com.skbingegalaxy.common.exception.ResourceNotFoundException(
                "Settlement", "id", settlementId));

        if (record.getSettlementStatus() == SettlementRecord.SettlementStatus.WRITTEN_OFF) {
            throw new BusinessException("This settlement was written off.");
        }
        if (actualMinor < 0) {
            throw new BusinessException("A payout cannot be negative.");
        }

        long expected = record.getOutstandingMinor();
        long variance = actualMinor - expected;

        record.setActualPayoutMinor(actualMinor);
        record.setActualPayoutAt(paidOn);
        record.setVarianceMinor(variance);
        record.setSettlementStatus(
            variance == 0 ? SettlementRecord.SettlementStatus.PAID
          : variance < 0 ? SettlementRecord.SettlementStatus.SHORT_PAID
                         : SettlementRecord.SettlementStatus.OVER_PAID);
        record.setReconciliationStatus(
            variance == 0 ? SettlementRecord.ReconciliationStatus.MATCHED
                          : SettlementRecord.ReconciliationStatus.VARIANCE);

        if (variance != 0) {
            log.warn("Settlement {} variance {} minor units ({} expected, {} paid)",
                settlementId, variance, expected, actualMinor);
        }
        return settlementRepository.save(record);
    }
}
