package com.skbingegalaxy.payment.listener;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.CashPaymentRequestedEvent;
import com.skbingegalaxy.payment.dto.RecordCashPaymentRequest;
import com.skbingegalaxy.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CashPaymentEventListener {

    private final PaymentService paymentService;

    @KafkaListener(topics = KafkaTopics.BOOKING_CASH_PAYMENT, groupId = "payment-group")
    public void onCashPaymentRequested(CashPaymentRequestedEvent event) {
        log.info("Received cash payment event for booking: {}", event.getBookingRef());
        try {
            RecordCashPaymentRequest request = RecordCashPaymentRequest.builder()
                .bookingRef(event.getBookingRef())
                .customerId(event.getCustomerId())
                .amount(event.getAmount())
                .notes(event.getNotes())
                .build();

            paymentService.recordCashPayment(request, "SYSTEM");
            log.info("Cash payment ledger record created for booking: {}", event.getBookingRef());
        } catch (Exception e) {
            log.error("Failed to create cash payment record for booking {}: {}",
                event.getBookingRef(), e.getMessage(), e);
        }
    }
}
