package com.skbingegalaxy.payment.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cleans up orphaned INITIATED payments when a booking is cancelled
 * (e.g. by the pending-timeout scheduler or by an admin).
 *
 * Without this listener, INITIATED payment records remain indefinitely,
 * and a late Razorpay callback could arrive for an already-cancelled booking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCancelledEventListener {

    private final PaymentRepository paymentRepository;

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "payment-group")
    @Transactional
    public void onBookingCancelled(BookingEvent event) {
        String bookingRef = event.getBookingRef();
        log.info("Booking cancelled event received for: {}", bookingRef);

        List<Payment> initiatedPayments =
                paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.INITIATED);

        for (Payment payment : initiatedPayments) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Booking cancelled");
            paymentRepository.save(payment);
            log.info("Marked INITIATED payment {} as FAILED for cancelled booking {}",
                    payment.getTransactionId(), bookingRef);
        }
    }
}
