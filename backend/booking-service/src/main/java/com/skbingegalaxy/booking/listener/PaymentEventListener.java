package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.entity.ProcessedEvent;
import com.skbingegalaxy.booking.entity.SagaState;
import com.skbingegalaxy.booking.repository.ProcessedEventRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.BookingAnalyticsMetrics;
import com.skbingegalaxy.booking.service.SagaOrchestrator;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

/**
 * Saga participant: reacts to payment events with compensating actions.
 * All handlers are idempotent — duplicate events are safely skipped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final BookingService bookingService;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final AdminEventBus adminEventBus;
    private final BookingEventLogService eventLogService;
    private final BookingAnalyticsMetrics analyticsMetrics;

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCESS, groupId = "booking-group")
    @Transactional
    public void onPaymentSuccess(PaymentEvent event) {
        String key = "PAYMENT_SUCCESS:" + event.getBookingRef() + ":" + event.getTransactionId();
        if (isDuplicate(key)) return;

        log.info("Payment success event for booking: {}", event.getBookingRef());

        // ── Out-of-order guard (Item 26) ───────────────────────────────────
        // payment.success can legitimately arrive AFTER the booking has
        // already been cancelled (pending-timeout scheduler raced the user's
        // payment) or marked NO_SHOW/COMPLETED via the daily audit. Recording
        // the money still has to happen — refusing the event would leave the
        // ledger out of sync with the gateway — but we MUST NOT advance the
        // saga (already compensated) and we MUST flag the row for ops to
        // initiate a refund. The processed-event dedup at the top still
        // protects against duplicate deliveries.
        Booking pre = bookingService.getBookingEntityForSystem(event.getBookingRef());

        // FX lock expiry audit: if an FX rate was committed at booking creation
        // (fxLockedUntil is set) and that window has now passed, log a WARNING for
        // reconciliation. In a well-functioning system this should never fire because
        // consumeLock() at booking creation already validated the lock; this catches
        // edge cases (clock skew, bypass, very long payment abandonment then retry).
        if (pre.getFxLockedUntil() != null
                && pre.getFxLockedUntil().isBefore(java.time.LocalDateTime.now(ZoneOffset.UTC))) {
            log.warn("FX_RATE_EXPIRY_AT_PAYMENT: booking {} fxLockedUntil={} already passed "
                + "before PAYMENT_SUCCESS arrived (txn {}). Possible rate mismatch — "
                + "check invoice vs Razorpay settlement.",
                event.getBookingRef(), pre.getFxLockedUntil(), event.getTransactionId());
        }

        boolean terminal = pre.getStatus() == BookingStatus.CANCELLED
                        || pre.getStatus() == BookingStatus.NO_SHOW
                        || pre.getStatus() == BookingStatus.COMPLETED;

        // Add the amount atomically first, then re-read to make the status decision.
        // This avoids the stale-read race where two concurrent payment events both read
        // collectedAmount=0, both compute partial, and both set PARTIALLY_PAID even though
        // the full amount has been collected. With clearAutomatically=true on the repo
        // @Modifying, the subsequent getBookingEntity call goes to the DB for a fresh read.
        bookingService.addToCollectedAmount(event.getBookingRef(), event.getAmount());

        Booking booking = bookingService.getBookingEntityForSystem(event.getBookingRef());
        java.math.BigDecimal collected = booking.getCollectedAmount() != null
            ? booking.getCollectedAmount() : java.math.BigDecimal.ZERO;

        if (terminal) {
            log.warn("PAYMENT_AFTER_TERMINAL: booking {} is {} but received payment {} (txn {}). "
                + "Operator review required for refund.",
                event.getBookingRef(), pre.getStatus(), event.getAmount(), event.getTransactionId());
            // Reflect that money was received but DO NOT touch the lifecycle
            // status. Use PARTIALLY_PAID/SUCCESS strictly to make the ledger
            // queryable; saga is intentionally NOT advanced.
            PaymentStatus reconciled = booking.getTotalAmount() != null
                && collected.compareTo(booking.getTotalAmount()) < 0
                    ? PaymentStatus.PARTIALLY_PAID
                    : PaymentStatus.SUCCESS;
            bookingService.updatePaymentStatus(event.getBookingRef(), reconciled, event.getPaymentMethod());
            try {
                Booking refreshed = bookingService.getBookingEntityForSystem(event.getBookingRef());
                eventLogService.logEvent(refreshed, BookingEventType.PAYMENT_SUCCEEDED,
                    refreshed.getStatus().name(), null, "SYSTEM",
                    String.format("Payment %s received AFTER booking entered %s (txn %s) — refund required",
                        event.getAmount(), pre.getStatus(), event.getTransactionId()));
                eventLogService.logEvent(refreshed, BookingEventType.MANUAL_REVIEW_FLAGGED,
                    refreshed.getStatus().name(), null, "SYSTEM",
                    "PAYMENT_AFTER_TERMINAL: out-of-order payment.success after " + pre.getStatus());
            } catch (Exception ex) {
                log.warn("Timeline log for PAYMENT_AFTER_TERMINAL failed for {}: {}",
                    event.getBookingRef(), ex.getMessage());
            }
            adminEventBus.publish(booking.getBingeId(), "booking", java.util.Map.of(
                "type", "payment.after-terminal",
                "ref", event.getBookingRef(),
                "prevStatus", pre.getStatus().name(),
                "ts", System.currentTimeMillis()));
            // Count the gateway-side success regardless of saga state — the
            // metric measures "money received", not "saga progressed".
            analyticsMetrics.paymentSuccess();
            markProcessed(key);
            return;
        }

        boolean fullyPaid = booking.getTotalAmount() == null
                || collected.compareTo(booking.getTotalAmount()) >= 0;
        if (!fullyPaid) {
            log.info("Partial payment for {}: collected={} of {}",
                event.getBookingRef(), collected, booking.getTotalAmount());
            bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.PARTIALLY_PAID, event.getPaymentMethod());
        } else {
            bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.SUCCESS, event.getPaymentMethod());
        }

        sagaOrchestrator.advanceTo(event.getBookingRef(),
            SagaState.SagaStatus.PAYMENT_RECEIVED, "PAYMENT_SUCCESS");
        // On full payment, updatePaymentStatus() already drove the booking PENDING→CONFIRMED
        // via the state machine. Advance the saga to CONFIRMED to match so its lifecycle isn't
        // stuck at PAYMENT_RECEIVED, AND so the orchestrator's collected≥total underpayment
        // guard actually executes (it was previously dead code — advanceTo(CONFIRMED) was
        // never called anywhere). A partial payment leaves the booking PENDING, so the saga
        // correctly stays at PAYMENT_RECEIVED until the balance is settled.
        if (fullyPaid) {
            sagaOrchestrator.advanceTo(event.getBookingRef(),
                SagaState.SagaStatus.CONFIRMED, "BOOKING_CONFIRMED");
        }
        analyticsMetrics.paymentSuccess();
        adminEventBus.publish(booking.getBingeId(), "booking", java.util.Map.of(
            "type", "payment.success", "ref", event.getBookingRef(), "ts", System.currentTimeMillis()));

        // Timeline event for customer + admin views. We log AFTER the booking
        // is mutated so newStatus reflects the post-payment state. Failures
        // here MUST NOT roll back the saga progression — the audit logger
        // already runs in REQUIRES_NEW so swallow only fatal callback issues.
        try {
            Booking refreshed = bookingService.getBookingEntityForSystem(event.getBookingRef());
            String desc = String.format("Payment %s received via %s (txn %s)",
                event.getAmount(),
                event.getPaymentMethod() != null ? event.getPaymentMethod() : "UNKNOWN",
                event.getTransactionId());
            eventLogService.logEvent(refreshed, BookingEventType.PAYMENT_SUCCEEDED,
                refreshed.getStatus().name(), null, "SYSTEM", desc);
        } catch (Exception ex) {
            log.warn("Timeline log for PAYMENT_SUCCEEDED failed for {}: {}",
                event.getBookingRef(), ex.getMessage());
        }
        markProcessed(key);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "booking-group")
    @Transactional
    public void onPaymentFailed(PaymentEvent event) {
        String key = "PAYMENT_FAILED:" + event.getBookingRef() + ":" + event.getTransactionId();
        if (isDuplicate(key)) return;

        log.info("Payment failed event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.FAILED, null);
        analyticsMetrics.paymentFailed();

        try {
            Booking refreshed = bookingService.getBookingEntityForSystem(event.getBookingRef());
            String reason = event.getStatus() != null ? event.getStatus() : "Gateway reported failure";
            eventLogService.logEvent(refreshed, BookingEventType.PAYMENT_FAILED,
                refreshed.getStatus().name(), null, "SYSTEM",
                "Payment failed: " + reason
                    + (event.getTransactionId() != null ? " (txn " + event.getTransactionId() + ")" : ""));
        } catch (Exception ex) {
            log.warn("Timeline log for PAYMENT_FAILED failed for {}: {}",
                event.getBookingRef(), ex.getMessage());
        }

        try {
            Booking booking = bookingService.getBookingEntityForSystem(event.getBookingRef());
            if (booking.getStatus() == BookingStatus.CANCELLED) {
                // Already cancelled (e.g. by the pending-timeout scheduler) — compensation is done.
                sagaOrchestrator.advanceTo(event.getBookingRef(),
                    SagaState.SagaStatus.COMPENSATING, "PAYMENT_FAILED_BOOKING_ALREADY_CANCELLED");
                sagaOrchestrator.advanceTo(event.getBookingRef(),
                    SagaState.SagaStatus.COMPENSATED, "BOOKING_ALREADY_CANCELLED");
                log.info("Booking {} already cancelled — saga marked compensated after payment failure",
                    event.getBookingRef());
            } else if (booking.getStatus() == BookingStatus.PENDING) {
                sagaOrchestrator.markCompensating(event.getBookingRef(), "Payment failed");
                bookingService.cancelBookingForSystem(
                    event.getBookingRef(),
                    "Booking auto-cancelled after payment failure");
                sagaOrchestrator.advanceTo(event.getBookingRef(),
                    SagaState.SagaStatus.COMPENSATED, "BOOKING_CANCELLED_AFTER_PAYMENT_FAIL");
                log.info("Saga compensation: auto-cancelled PENDING booking {} after payment failure",
                    event.getBookingRef());
            }
        } catch (Exception e) {
            sagaOrchestrator.markFailed(event.getBookingRef(),
                "Compensation failed: " + e.getMessage());
            log.error("Saga compensation FAILED for booking {} after payment failure",
                event.getBookingRef(), e);
            throw new IllegalStateException(
                "Failed to compensate booking after payment failure for " + event.getBookingRef(), e);
        }
        markProcessed(key);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUNDED, groupId = "booking-group")
    @Transactional
    public void onPaymentRefunded(PaymentEvent event) {
        String key = "PAYMENT_REFUNDED:" + event.getBookingRef() + ":" + event.getRefundId();
        if (isDuplicate(key)) return;

        log.info("Payment refunded event for booking: {}, status: {}", event.getBookingRef(), event.getStatus());
        // Apply the refund to the running collected total FIRST, then derive the
        // booking-level payment status from the NET position (collected vs total) —
        // never from the single payment's refund flag alone. This makes the status
        // self-correcting regardless of the order in which payment.refunded and
        // payment.success arrive, which is essential for the "change payment method"
        // flow: it fires a full refund of the old payment AND an immediate
        // re-collection under the new method, on two different Kafka topics with no
        // cross-topic ordering guarantee. The previous logic set REFUNDED purely from
        // event.getStatus(), so if the re-collection's payment.success was processed
        // first, the booking stayed stuck at REFUNDED with collected == total — the
        // phantom "fully refunded, ₹0 collected" banner on a fully-paid booking.
        bookingService.subtractFromCollectedAmount(event.getBookingRef(), event.getRefundAmount());

        Booking netBooking = bookingService.getBookingEntityForSystem(event.getBookingRef());
        java.math.BigDecimal collected = netBooking.getCollectedAmount() != null
            ? netBooking.getCollectedAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = netBooking.getTotalAmount() != null
            ? netBooking.getTotalAmount() : java.math.BigDecimal.ZERO;
        final java.math.BigDecimal epsilon = new java.math.BigDecimal("0.01");

        PaymentStatus status;
        if (collected.compareTo(epsilon) <= 0) {
            status = PaymentStatus.REFUNDED;            // nothing left collected → genuinely refunded
        } else if (total.signum() > 0 && collected.compareTo(total) >= 0) {
            status = PaymentStatus.SUCCESS;             // still (or again) fully covered, e.g. a method swap
        } else {
            status = PaymentStatus.PARTIALLY_REFUNDED;  // some money refunded, a balance is still retained
        }
        bookingService.updatePaymentStatus(event.getBookingRef(), status, null);

        try {
            Booking refreshed = bookingService.getBookingEntityForSystem(event.getBookingRef());
            String desc = String.format("Refund %s processed (refund id %s, status %s)",
                event.getRefundAmount(),
                event.getRefundId() != null ? event.getRefundId() : "-",
                status.name());
            eventLogService.logEvent(refreshed, BookingEventType.REFUND_COMPLETED,
                refreshed.getStatus().name(), null, "SYSTEM", desc);
        } catch (Exception ex) {
            log.warn("Timeline log for REFUND_COMPLETED failed for {}: {}",
                event.getBookingRef(), ex.getMessage());
        }
        markProcessed(key);
    }

    private boolean isDuplicate(String eventKey) {
        if (processedEventRepository.existsByEventKey(eventKey)) {
            log.info("Duplicate event skipped: {}", eventKey);
            return true;
        }
        return false;
    }

    private void markProcessed(String eventKey) {
        processedEventRepository.save(ProcessedEvent.builder().eventKey(eventKey).build());
    }
}
