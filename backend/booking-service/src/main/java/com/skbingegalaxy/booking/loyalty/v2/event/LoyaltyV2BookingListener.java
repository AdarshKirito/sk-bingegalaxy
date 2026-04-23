package com.skbingegalaxy.booking.loyalty.v2.event;

import com.skbingegalaxy.booking.event.BookingCancelledEvent;
import com.skbingegalaxy.booking.event.BookingCompletedEvent;
import com.skbingegalaxy.booking.loyalty.v2.engine.EarnEngine;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.TierEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyLedgerEntry;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyLedgerEntryRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants.LEDGER_EARN;

/**
 * Loyalty v2 — listener that bridges in-process booking events into
 * the v2 wallet / tier engines.
 *
 * <p>Every handler uses {@link TransactionalEventListener} with
 * {@link TransactionPhase#AFTER_COMMIT} — the listener only fires
 * after the booking transaction commits, so a rolled-back booking
 * cannot leak loyalty side-effects.  Each listener then opens its own
 * {@link Propagation#REQUIRES_NEW} transaction so a loyalty failure
 * cannot roll back the already-committed booking.
 *
 * <p>{@link Async} decouples loyalty work from the booking request
 * thread — a slow tier recalc won't delay a customer confirmation.
 *
 * <p><b>Failure handling:</b> loyalty is best-effort.  If a listener
 * throws, the exception is logged and swallowed — the booking stays
 * committed and a follow-up can reconcile (the LEDGER_EARN idempotency
 * key means a retry is safe).  We do NOT wrap in
 * {@code @Retryable} because that couples latency; we prefer explicit
 * reconciliation jobs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LoyaltyV2BookingListener {

    private final EarnEngine earnEngine;
    private final TierEngine tierEngine;
    private final PointsWalletService walletService;
    private final EnrollmentService enrollmentService;
    private final LoyaltyLedgerEntryRepository ledgerRepository;
    private final LoyaltyPointsWalletRepository walletRepository;

    // ── EARN on booking completion ───────────────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCompleted(BookingCompletedEvent evt) {
        try {
            LoyaltyMembership membership = enrollmentService.ensureEnrolledForBooking(evt.customerId());
            EarnEngine.EarnResult result = earnEngine.earnForBooking(new EarnEngine.EarnRequest(
                    membership.getId(),
                    evt.bingeId(),
                    evt.bookingRef(),
                    evt.totalAmount(),
                    evt.completedAt(),
                    "booking=" + evt.bookingRef()
            ));
            if (result.awarded()) {
                log.info("[loyalty-v2] booking {} → membership {} earned {} pts, {} QC",
                        evt.bookingRef(), membership.getId(),
                        result.pointsAwarded(), result.qualifyingCreditsAwarded());
            } else {
                log.info("[loyalty-v2] booking {} → earn skipped: {}", evt.bookingRef(), result.skipReason());
            }
        } catch (Exception ex) {
            log.error("[loyalty-v2] earn listener failed for booking {}: {}",
                    evt.bookingRef(), ex.getMessage(), ex);
        }
    }

    // ── REVERSAL on booking cancellation ─────────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelled(BookingCancelledEvent evt) {
        try {
            LoyaltyMembership membership = enrollmentService.findForCustomer(evt.customerId()).orElse(null);
            if (membership == null) return;                                 // nothing to reverse

            LoyaltyPointsWallet wallet = walletRepository.findByMembershipId(membership.getId()).orElse(null);
            if (wallet == null) return;

            // Find the original EARN entries for this booking.  If the
            // booking was never earned against (e.g. cancelled pre-completion),
            // the list is empty and we no-op.
            List<LoyaltyLedgerEntry> earnEntries = ledgerRepository
                    .findByWalletIdAndBookingRefAndEntryType(
                            wallet.getId(), evt.bookingRef(), LEDGER_EARN);
            if (earnEntries.isEmpty()) {
                log.debug("[loyalty-v2] cancel {} — no EARN entries to reverse", evt.bookingRef());
                return;
            }

            long totalEarned = earnEntries.stream().mapToLong(LoyaltyLedgerEntry::getPointsDelta).sum();
            BigDecimal proportion = (evt.totalAmount() != null && evt.totalAmount().signum() > 0
                    && evt.refundAmount() != null)
                    ? evt.refundAmount().divide(evt.totalAmount(), 4, RoundingMode.FLOOR)
                    : BigDecimal.ONE;
            long pointsToReverse = new BigDecimal(totalEarned).multiply(proportion)
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
            if (pointsToReverse <= 0) return;

            String idempKey = "reverse-earn:booking=" + evt.bookingRef();
            walletService.debit(new PointsWalletService.DebitRequest(
                    membership.getId(),
                    pointsToReverse,
                    "REVERSE_EARN",
                    evt.bingeId(),
                    evt.bookingRef(),
                    idempKey,
                    "booking=" + evt.bookingRef(),
                    "CANCELLATION",
                    "Reversal of earn for cancelled booking " + evt.bookingRef(),
                    null,
                    "SYSTEM"
            ));

            tierEngine.recalculateTier(membership.getId(), evt.cancelledAt());
            log.info("[loyalty-v2] cancel {} → reversed {} pts (of {} earned, {} refund ratio)",
                    evt.bookingRef(), pointsToReverse, totalEarned, proportion);
        } catch (Exception ex) {
            log.error("[loyalty-v2] cancel listener failed for booking {}: {}",
                    evt.bookingRef(), ex.getMessage(), ex);
        }
    }
}
