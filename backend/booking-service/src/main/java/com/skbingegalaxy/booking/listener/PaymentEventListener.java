package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final BookingService bookingService;

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCESS, groupId = "booking-group")
    public void onPaymentSuccess(PaymentEvent event) {
        log.info("Payment success event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.SUCCESS);
        bookingService.addToCollectedAmount(event.getBookingRef(), event.getAmount());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "booking-group")
    public void onPaymentFailed(PaymentEvent event) {
        log.info("Payment failed event for booking: {}", event.getBookingRef());
        bookingService.updatePaymentStatus(event.getBookingRef(), PaymentStatus.FAILED);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUNDED, groupId = "booking-group")
    public void onPaymentRefunded(PaymentEvent event) {
        log.info("Payment refunded event for booking: {}, status: {}", event.getBookingRef(), event.getStatus());
        PaymentStatus status = PaymentStatus.PARTIALLY_REFUNDED.name().equals(event.getStatus())
            ? PaymentStatus.PARTIALLY_REFUNDED
            : PaymentStatus.REFUNDED;
        bookingService.updatePaymentStatus(event.getBookingRef(), status);
        bookingService.subtractFromCollectedAmount(event.getBookingRef(), event.getRefundAmount());
    }
}
