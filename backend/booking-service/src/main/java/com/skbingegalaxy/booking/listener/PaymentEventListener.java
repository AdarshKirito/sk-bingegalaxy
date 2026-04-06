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
        String key = "PAYMENT_SUCCESS:" + event.getBookingRef() + ":" + event.getAmount();
        if (isDuplicate(key)) return;

        log.info("Payment success event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.SUCCESS, event.getPaymentMethod());
        bookingService.addToCollectedAmount(event.getBookingRef(), event.getAmount());
        sagaOrchestrator.advanceTo(event.getBookingRef(),
            SagaState.SagaStatus.PAYMENT_RECEIVED, "PAYMENT_SUCCESS");
        markProcessed(key);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "booking-group")
    @Transactional
    public void onPaymentFailed(PaymentEvent event) {
        String key = "PAYMENT_FAILED:" + event.getBookingRef();
        if (isDuplicate(key)) return;

        log.info("Payment failed event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.FAILED, null);

        try {
            Booking booking = bookingService.getBookingEntity(event.getBookingRef());
            if (booking.getStatus() == BookingStatus.PENDING) {
                sagaOrchestrator.markCompensating(event.getBookingRef(), "Payment failed");
                bookingService.cancelBooking(event.getBookingRef());
                sagaOrchestrator.advanceTo(event.getBookingRef(),
                    SagaState.SagaStatus.COMPENSATED, "BOOKING_CANCELLED_AFTER_PAYMENT_FAIL");
                log.info("Saga compensation: auto-cancelled PENDING booking {} after payment failure",
                    event.getBookingRef());
            }
        } catch (Exception e) {
            sagaOrchestrator.markFailed(event.getBookingRef(),
                "Compensation failed: " + e.getMessage());
            log.error("Saga compensation FAILED for booking {} after payment failure: {}",
                event.getBookingRef(), e.getMessage());
        }
        markProcessed(key);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUNDED, groupId = "booking-group")
    @Transactional
    public void onPaymentRefunded(PaymentEvent event) {
        String key = "PAYMENT_REFUNDED:" + event.getBookingRef() + ":" + event.getRefundAmount();
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
