package com.skbingegalaxy.payment.scheduler;

import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.payment.entity.OutboxEvent;
import com.skbingegalaxy.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox_event table and publishes unsent events to Kafka.
 * Runs every 2 seconds with distributed locking (ShedLock) to prevent
 * duplicate publishing across replicas.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "paymentOutboxPublisher", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findTop100BySentFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                Object payload = objectMapper.readValue(event.getPayload(), PaymentEvent.class);
                kafkaTemplate.send(event.getTopic(), event.getAggregateKey(), payload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
                outboxRepo.save(event);
                published++;
            } catch (Exception e) {
                log.error("Outbox: failed to publish event {} to {}: {}",
                    event.getId(), event.getTopic(), e.getMessage());
                break; // Stop on first failure to preserve ordering
            }
        }
        if (published > 0) {
            log.debug("Payment outbox: published {} events", published);
        }
    }
}
