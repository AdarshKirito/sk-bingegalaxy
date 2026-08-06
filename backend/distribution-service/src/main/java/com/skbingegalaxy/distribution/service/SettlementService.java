package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.SettlementRecord;
import com.skbingegalaxy.distribution.repository.SettlementRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Create the receivable for a channel reservation that has just become a booking.
     *
     * <p><b>Why this lives in distribution-service and not booking-service.</b> The
     * commercial terms — commission rate, who collects, settlement model — belong to the
     * {@code ConnectionDestination}, which booking-service knows nothing about and
     * should not. Putting the calculation there would mean either duplicating the terms
     * across a service boundary or calling back for them on every booking. The inbox
     * already holds the connection, the destination and the booking reference at the
     * moment a reservation is applied, so this is the one place with everything needed.
     *
     * <p><b>A receivable is only created when the CHANNEL collects.</b> If the venue
     * takes the money at the door, nothing is owed TO the venue — the venue owes
     * commission, which is a payable and a different object with different chasing
     * behaviour. Recording it here as a receivable would invert the direction of the
     * money and inflate every "outstanding" total by the commission the venue owes.
     *
     * <p>Idempotent by {@code uk_settlement_booking_dest}: a redelivered provider message
     * must not create a second receivable for the same booking.
     */
    @Transactional
    public Optional<SettlementRecord> createForChannelBooking(
            Long bingeId, Long connectionId, String bookingRef,
            ConnectionDestination terms, String currency, long grossMinor) {

        if (terms.getPaymentResponsibility()
                != ConnectionDestination.PaymentResponsibility.CHANNEL_COLLECTS) {
            log.debug("No receivable for {}: {} collects, not the channel",
                bookingRef, terms.getPaymentResponsibility());
            return Optional.empty();
        }
        if (grossMinor < 0) {
            throw new BusinessException("Gross amount cannot be negative.");
        }

        // Already recorded — a redelivered message, not a second sale.
        Optional<SettlementRecord> existing = settlementRepository
            .findByBookingRefAndDestinationCode(bookingRef, terms.getDestinationCode());
        if (existing.isPresent()) return existing;

        long commission = commissionOf(grossMinor, terms.getCommissionBps());
        long venueNet = grossMinor - commission;

        SettlementRecord record = SettlementRecord.builder()
            .bookingRef(bookingRef)
            .bingeId(bingeId)
            .connectionId(connectionId)
            .destinationCode(terms.getDestinationCode())
            .settlementCurrency(currency)
            .grossMinor(grossMinor)
            .commissionMinor(commission)
            .venueNetMinor(venueNet)
            // Outstanding is the NET, not the gross: the commission is never coming to
            // the venue, so counting it as owed would overstate every total by the
            // channel's cut.
            .outstandingMinor(venueNet)
            .collectedBy(SettlementRecord.CollectedBy.CHANNEL)
            // EXPECTED, not PENDING: the terms are known and the money is genuinely
            // anticipated. PENDING is for a receivable whose amount is not yet settled.
            .settlementStatus(SettlementRecord.SettlementStatus.EXPECTED)
            .reconciliationStatus(SettlementRecord.ReconciliationStatus.UNMATCHED)
            .build();

        log.info("Receivable created for {} on {}: gross {} commission {} net {} {}",
            bookingRef, terms.getDestinationCode(), grossMinor, commission, venueNet, currency);
        return Optional.of(settlementRepository.save(record));
    }

    /**
     * Commission in minor units, rounded HALF-UP on integer arithmetic.
     *
     * <p>Computed from the gross rather than derived by subtracting a net, so the
     * rounding happens once and in a known direction. Half-up rather than truncation
     * because truncating systematically favours the venue by up to a unit per booking —
     * small, but it is a discrepancy that compounds across every reconciliation and
     * costs more to explain to a channel than it is worth.
     */
    static long commissionOf(long grossMinor, Integer commissionBps) {
        if (commissionBps == null || commissionBps <= 0) return 0L;
        return (grossMinor * commissionBps + 5_000L) / 10_000L;
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
