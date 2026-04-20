package com.skbingegalaxy.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.payment.entity.OutboxEvent;
import com.skbingegalaxy.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Saves {@link PaymentKafkaEvent}s to the outbox table <strong>before</strong>
 * the enclosing database transaction commits. A separate
 * {@link com.skbingegalaxy.payment.scheduler.OutboxPublisher} polls the outbox
 * and publishes to Kafka, eliminating the dual-write problem entirely.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentEvent(PaymentKafkaEvent event) {
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                .topic(event.topic())
                .aggregateKey(event.key())
                .payload(objectMapper.writeValueAsString(event.payload()))
                .build());
            log.debug("Saved outbox event {} for booking: {} (before-commit)", event.topic(), event.key());
        } catch (Exception e) {
            log.error("Failed to save outbox event for {}: {}", event.key(), e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }
}
