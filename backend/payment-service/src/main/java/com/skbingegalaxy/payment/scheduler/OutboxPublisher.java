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

    /**
     * After this many consecutive failures, an outbox event is marked {@code failed_permanent=true}
     * and skipped by subsequent runs. This prevents a single poison-pill event from blocking
     * the entire outbox queue indefinitely. Failed-permanent events must be investigated
     * and retried/discarded by operators.
     */
    static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "paymentOutboxPublisher", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        int published = 0;
        int poisoned = 0;
        for (OutboxEvent event : pending) {
            try {
                Object payload = objectMapper.readValue(event.getPayload(), PaymentEvent.class);
                kafkaTemplate.send(event.getTopic(), event.getAggregateKey(), payload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
                event.setLastAttemptAt(LocalDateTime.now());
                outboxRepo.save(event);
                published++;
            } catch (Exception e) {
                // Record the failure but do NOT halt the entire batch — a single poison-pill event
                // must not block all subsequent events (head-of-line blocking). Per-aggregate
                // ordering is still preserved at the Kafka partition level (same aggregateKey
                // goes to the same partition).
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastAttemptAt(LocalDateTime.now());
                String msg = e.getMessage();
                event.setLastError(msg != null && msg.length() > 1000 ? msg.substring(0, 1000) : msg);

                if (attempts >= MAX_ATTEMPTS) {
                    event.setFailedPermanent(true);
                    log.error("Outbox: event {} to {} marked FAILED_PERMANENT after {} attempts. "
                            + "Needs manual review. Last error: {}",
                        event.getId(), event.getTopic(), attempts, msg);
                    poisoned++;
                } else {
                    log.warn("Outbox: event {} to {} failed on attempt {}/{}. Will retry. Error: {}",
                        event.getId(), event.getTopic(), attempts, MAX_ATTEMPTS, msg);
                }
                outboxRepo.save(event);
                // Continue processing subsequent events. Skip this one for now.
            }
        }
        if (published > 0 || poisoned > 0) {
            log.debug("Payment outbox: published={}, poisoned={}", published, poisoned);
        }
    }
}
