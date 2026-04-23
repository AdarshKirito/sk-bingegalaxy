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
 * duplicate publishing across replicas. On a Kafka send failure we
 * <em>continue</em> past the offending event (tracking attempts and
 * last error on the row); once {@link #MAX_ATTEMPTS} is exhausted the
 * event is marked {@code failedPermanent=true} and excluded from future
 * batches. Per-aggregate ordering is preserved by Kafka keying on
 * {@code aggregateKey} (same key → same partition). SSE events are
 * pushed to the admin dashboard after each successful publish.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ERROR_LEN = 1000;

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AdminEventBus adminEventBus;

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "outboxPublisher", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        int published = 0;
        int failed = 0;
        for (OutboxEvent event : pending) {
            try {
                Object payload = toKafkaPayload(event);
                kafkaTemplate.send(event.getTopic(), event.getAggregateKey(), payload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setSent(true);
                event.setSentAt(LocalDateTime.now());
                event.setLastError(null);
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
                failed++;
                event.setAttempts(event.getAttempts() + 1);
                event.setLastAttemptAt(LocalDateTime.now());
                event.setLastError(truncate(e.getMessage()));
                if (event.getAttempts() >= MAX_ATTEMPTS) {
                    event.setFailedPermanent(true);
                    log.error("Outbox: event {} to {} marked failedPermanent after {} attempts: {}",
                        event.getId(), event.getTopic(), event.getAttempts(), event.getLastError());
                } else {
                    log.warn("Outbox: event {} to {} failed attempt {}/{}: {}",
                        event.getId(), event.getTopic(), event.getAttempts(), MAX_ATTEMPTS,
                        event.getLastError());
                }
                outboxRepo.save(event);
                // Continue to the next event — same-key ordering is preserved by Kafka partitioning.
            }
        }
        if (published > 0 || failed > 0) {
            log.debug("Outbox: published={} failed={}", published, failed);
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_ERROR_LEN ? s.substring(0, MAX_ERROR_LEN) : s;
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
