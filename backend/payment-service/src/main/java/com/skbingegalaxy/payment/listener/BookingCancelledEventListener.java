package com.skbingegalaxy.payment.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.entity.RefundStatus;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import com.skbingegalaxy.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Saga participant for {@code booking.cancelled} (BOOK-004).
 *
 * <ol>
 *   <li>Orphaned INITIATED payments are failed so a late gateway callback for
 *       the cancelled booking routes through the late-capture auto-refund.</li>
 *   <li>When the event carries a {@code refundAmount} (the cancellation
 *       policy's share of the COLLECTED money), real refund intents are
 *       reserved against the booking's captured payments and executed through
 *       the PAY-006 durable-intent pipeline. The resulting
 *       {@code payment.refunded} events are what reduce the booking's
 *       collected amount — booking-service no longer zeroes it locally.</li>
 * </ol>
 *
 * <p>Idempotency: cancellation refunds carry a deterministic reason marker.
 * On redelivery the outstanding amount is recomputed net of every live or
 * settled cancellation refund, so re-processing converges to zero instead of
 * refunding twice. The per-payment over-refund guard in
 * {@link PaymentService#reserveRefundIntent} is the final backstop.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCancelledEventListener {

    /** Deterministic marker — also the idempotency key for redeliveries. */
    static final String CANCELLATION_REFUND_REASON = "Cancellation refund (policy)";

    private static final Set<RefundStatus> LIVE_OR_SETTLED = EnumSet.of(
        RefundStatus.INITIATED, RefundStatus.PROCESSING, RefundStatus.SUCCEEDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentService paymentService;

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "payment-group")
    @Transactional
    public void onBookingCancelled(BookingEvent event) {
        String bookingRef = event.getBookingRef();
        log.info("Booking cancelled event received for: {} (refundAmount={})",
            bookingRef, event.getRefundAmount());

        List<Payment> initiatedPayments =
                paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.INITIATED);
        for (Payment payment : initiatedPayments) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Booking cancelled");
            paymentRepository.save(payment);
            log.info("Marked INITIATED payment {} as FAILED for cancelled booking {}",
                    payment.getTransactionId(), bookingRef);
        }

        BigDecimal target = event.getRefundAmount();
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            return; // unpaid booking or 0% policy tier — nothing to move
        }

        // Outstanding = policy refund minus every cancellation refund already
        // reserved/settled for this booking (idempotent across redeliveries).
        BigDecimal alreadyClaimed = refundRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef).stream()
            .filter(r -> CANCELLATION_REFUND_REASON.equals(r.getReason()))
            .filter(r -> LIVE_OR_SETTLED.contains(r.getRefundStatus()))
            .map(Refund::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = target.subtract(alreadyClaimed);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Cancellation refund for {} already fully reserved ({} of {}) — idempotent skip",
                bookingRef, alreadyClaimed, target);
            return;
        }

        // Spread the refund across captured payments, newest first (the most
        // recent capture is the least likely to be settled/disputed).
        List<Payment> captured = new ArrayList<>();
        captured.addAll(paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.SUCCESS));
        captured.addAll(paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.PARTIALLY_REFUNDED));
        captured.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        List<Long> intentIds = new ArrayList<>();
        for (Payment payment : captured) {
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal claimed = refundRepository.sumByPaymentIdAndRefundStatusIn(
                payment.getId(), List.copyOf(LIVE_OR_SETTLED));
            BigDecimal refundable = payment.getAmount().subtract(claimed);
            if (refundable.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal slice = outstanding.min(refundable);
            try {
                // REQUIRES_NEW: the intent is durable even if this listener
                // transaction later fails — reconciliation completes it, and a
                // redelivery sees it as already claimed.
                Long intentId = paymentService.reserveRefundIntent(payment.getId(), slice,
                    CANCELLATION_REFUND_REASON, "SYSTEM", null, false);
                intentIds.add(intentId);
                outstanding = outstanding.subtract(slice);
                log.info("Cancellation refund intent {} reserved: {} against payment {} for booking {}",
                    intentId, slice, payment.getTransactionId(), bookingRef);
            } catch (Exception ex) {
                log.error("Could not reserve cancellation refund of {} against payment {} for {}: {}",
                    slice, payment.getTransactionId(), bookingRef, ex.getMessage());
            }
        }

        if (outstanding.compareTo(BigDecimal.ZERO) > 0) {
            log.error("CANCELLATION_REFUND_SHORTFALL: booking {} still owed {} after spreading across "
                + "captured payments (disputed/legacy rows?). Ops must issue the remainder manually.",
                bookingRef, outstanding);
        }

        // Provider legs run only after this transaction commits (PAY-006);
        // a crash in between leaves durable intents for reconciliation.
        if (!intentIds.isEmpty()) {
            Runnable processAll = () -> {
                for (Long intentId : intentIds) {
                    try {
                        paymentService.processReservedRefund(intentId, false);
                    } catch (Exception ex) {
                        log.error("Cancellation refund intent {} could not be processed now ({}). "
                            + "Reconciliation will complete it.", intentId, ex.getMessage());
                    }
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        processAll.run();
                    }
                });
            } else {
                processAll.run();
            }
        }
    }
}
