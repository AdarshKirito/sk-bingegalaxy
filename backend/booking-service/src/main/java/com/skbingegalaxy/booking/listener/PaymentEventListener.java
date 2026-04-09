package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.ProcessedEvent;
import com.skbingegalaxy.booking.entity.SagaState;
import com.skbingegalaxy.booking.repository.ProcessedEventRepository;
import com.skbingegalaxy.booking.service.BookingService;
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

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCESS, groupId = "booking-group")
    @Transactional
    public void onPaymentSuccess(PaymentEvent event) {
        String key = "PAYMENT_SUCCESS:" + event.getBookingRef() + ":" + event.getTransactionId();
        if (isDuplicate(key)) return;

        log.info("Payment success event for booking: {}", event.getBookingRef());

        // Add the amount atomically first, then re-read to make the status decision.
        // This avoids the stale-read race where two concurrent payment events both read
        // collectedAmount=0, both compute partial, and both set PARTIALLY_PAID even though
        // the full amount has been collected. With clearAutomatically=true on the repo
        // @Modifying, the subsequent getBookingEntity call goes to the DB for a fresh read.
        bookingService.addToCollectedAmount(event.getBookingRef(), event.getAmount());

        Booking booking = bookingService.getBookingEntityForSystem(event.getBookingRef());
        java.math.BigDecimal collected = booking.getCollectedAmount() != null
            ? booking.getCollectedAmount() : java.math.BigDecimal.ZERO;

        if (booking.getTotalAmount() != null
                && collected.compareTo(booking.getTotalAmount()) < 0) {
            log.info("Partial payment for {}: collected={} of {}",
                event.getBookingRef(), collected, booking.getTotalAmount());
            bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.PARTIALLY_PAID, event.getPaymentMethod());
        } else {
            bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.SUCCESS, event.getPaymentMethod());
        }

        sagaOrchestrator.advanceTo(event.getBookingRef(),
            SagaState.SagaStatus.PAYMENT_RECEIVED, "PAYMENT_SUCCESS");
        markProcessed(key);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "booking-group")
    @Transactional
    public void onPaymentFailed(PaymentEvent event) {
        String key = "PAYMENT_FAILED:" + event.getBookingRef() + ":" + event.getTransactionId();
        if (isDuplicate(key)) return;

        log.info("Payment failed event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.FAILED, null);

        try {
            Booking booking = bookingService.getBookingEntityForSystem(event.getBookingRef());
            if (booking.getStatus() == BookingStatus.PENDING) {
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
        PaymentStatus status = PaymentStatus.PARTIALLY_REFUNDED.name().equals(event.getStatus())
            ? PaymentStatus.PARTIALLY_REFUNDED
            : PaymentStatus.REFUNDED;
        bookingService.updatePaymentStatus(event.getBookingRef(), status, null);
        bookingService.subtractFromCollectedAmount(event.getBookingRef(), event.getRefundAmount());
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
