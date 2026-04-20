package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.BookingEvent;
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
 * <p>
 * Runs every 2 seconds with distributed locking (ShedLock) to prevent
 * duplicate publishing across replicas. On first Kafka send failure the
 * loop breaks immediately to preserve event ordering — already-sent events
 * in the batch are persisted via {@code saveAll} while the failed event
 * retries on the next poll. SSE events are pushed to the admin dashboard
 * after each successful publish.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AdminEventBus adminEventBus;

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "outboxPublisher", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findTop100BySentFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                Object payload = toKafkaPayload(event);
                kafkaTemplate.send(event.getTopic(), event.getAggregateKey(), payload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
                // Persist immediately after successful Kafka send to prevent
                // re-publishing this event if a later event in the batch fails.
                outboxRepo.save(event);
                published++;

                // Push to binge-scoped admin SSE stream for real-time dashboard updates
                Long bingeId = extractBingeId(event.getPayload());
                if (bingeId != null) {
                    adminEventBus.publish(bingeId, "booking", java.util.Map.of(
                        "type", event.getTopic(),
                        "ref", event.getAggregateKey(),
                        "ts", System.currentTimeMillis()
                    ));
                }
            } catch (Exception e) {
                log.error("Outbox: failed to publish event {} to {}: {}",
                    event.getId(), event.getTopic(), e.getMessage());
                break; // Stop on first failure to preserve ordering
            }
        }
        if (published > 0) {
            log.debug("Outbox: published {} events", published);
        }
    }

    private Object toKafkaPayload(OutboxEvent event) throws Exception {
        if (KafkaTopics.BOOKING_CREATED.equals(event.getTopic())
                || KafkaTopics.BOOKING_CONFIRMED.equals(event.getTopic())
                || KafkaTopics.BOOKING_CANCELLED.equals(event.getTopic())) {
            return objectMapper.readValue(event.getPayload(), BookingEvent.class);
        }
        return event.getPayload();
    }

    /**
     * Extract bingeId from the outbox event JSON payload.
     * Returns null if not present or not a booking event.
     * Logs a warning on parse failure for observability.
     */
    private Long extractBingeId(String payload) {
        try {
            var tree = objectMapper.readTree(payload);
            var node = tree.get("bingeId");
            return (node != null && !node.isNull()) ? node.asLong() : null;
        } catch (Exception e) {
            log.warn("Outbox: failed to extract bingeId from payload (SSE notification skipped): {}", e.getMessage());
            return null;
        }
    }
}
