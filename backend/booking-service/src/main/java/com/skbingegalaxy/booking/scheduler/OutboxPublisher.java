package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls the outbox_event table and publishes unsent events to Kafka.
 * Runs every 2 seconds. Each batch is published then marked as sent
 * within the same transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaRawTemplate;

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "outboxPublisher", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findTop100BySentFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        for (OutboxEvent event : pending) {
            try {
                kafkaRawTemplate.send(event.getTopic(), event.getAggregateKey(), event.getPayload());
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
            } catch (Exception e) {
                log.error("Outbox: failed to publish event {} to {}: {}",
                    event.getId(), event.getTopic(), e.getMessage());
                break; // Stop on first failure to preserve ordering
            }
        }
        outboxRepo.saveAll(pending);
        log.debug("Outbox: published {} events", pending.stream().filter(OutboxEvent::isSent).count());
    }
}
