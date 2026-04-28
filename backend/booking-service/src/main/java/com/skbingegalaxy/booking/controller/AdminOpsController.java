package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Ops endpoints for production-grade event recovery. Mirrors the pattern used by
 * Stripe/Uber/LinkedIn: never hand-edit DB rows, always replay the event that
 * was supposed to drive the state machine so downstream side-effects
 * (notifications, loyalty, saga transitions, audit log) all fire correctly.
 *
 * <ul>
 *   <li>{@code POST /replay-dlt}: re-publish poisoned records from a DLT topic
 *   back onto their source topic. Used after a consumer/serializer bug is fixed.</li>
 *   <li>{@code POST /outbox/retry-failed}: flip {@code failedPermanent=false}
 *   on outbox rows so the scheduler picks them up on the next tick.</li>
 *   <li>{@code GET /health}: quick pulse on the async event pipeline — DLT
 *   depth + outbox poisoned count. Wire to PagerDuty when any value &gt; 0.</li>
 * </ul>
 *
 * <p>Protected by SecurityConfig to {@code ROLE_ADMIN} / {@code ROLE_SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/ops")
@RequiredArgsConstructor
@Slf4j
public class AdminOpsController {

    /** Only these topics can be replayed from — a safety allow-list. */
    private static final Set<String> REPLAYABLE_SOURCE_TOPICS = Set.of(
        "payment.success-dlt",
        "payment.failed-dlt",
        "payment.refunded-dlt",
        "booking.cancelled-dlt",
        "booking.created-dlt",
        "booking.confirmed-dlt"
    );

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String bootstrapServers;

    /**
     * Drain up to {@code max} records from a DLT topic and re-publish them to
     * the original topic (derived by stripping {@code -dlt}). Uses a one-shot
     * consumer with a unique group-id so it always starts at earliest and
     * never interferes with real consumer group offsets.
     */
    @PostMapping("/replay-dlt")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replayDlt(
            @RequestParam String sourceTopic,
            @RequestParam(defaultValue = "100") int max) {

        if (!REPLAYABLE_SOURCE_TOPICS.contains(sourceTopic)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                "Source topic not in replay allow-list: " + sourceTopic));
        }
        if (max <= 0 || max > 1000) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                "max must be between 1 and 1000"));
        }
        String targetTopic = sourceTopic.substring(0, sourceTopic.length() - "-dlt".length());

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Unique group each call → always reads from the configured offset and
        // leaves main consumer groups untouched.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replayer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Read the raw payload bytes so we can republish them verbatim — this
        // is what Spring Kafka's DeadLetterPublishingRecoverer wrote, and
        // re-serializing via the new JSON config would re-create the bug we
        // just fixed on the producer side.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        int replayed = 0;
        int errors = 0;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(sourceTopic));
            long deadline = System.currentTimeMillis() + 15_000; // hard cap
            while (replayed + errors < max && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> batch = consumer.poll(Duration.ofMillis(1500));
                if (batch.isEmpty()) break;
                for (ConsumerRecord<String, byte[]> rec : batch) {
                    if (replayed + errors >= max) break;
                    try {
                        ProducerRecord<String, Object> out = new ProducerRecord<>(
                            targetTopic, null, rec.key(), rec.value());
                        // Preserve headers so downstream __TypeId__ mapping still works.
                        rec.headers().forEach(h -> {
                            // Skip the DLT-exception headers — they're only meaningful inside the DLT.
                            String k = h.key();
                            if (k.startsWith("kafka_dlt-") || k.startsWith("dlt-")) return;
                            out.headers().add(h);
                        });
                        kafkaTemplate.send(out).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        replayed++;
                        log.info("DLT replay: {} offset={} key={} → {}",
                            sourceTopic, rec.offset(), rec.key(), targetTopic);
                    } catch (Exception e) {
                        errors++;
                        log.error("DLT replay FAILED: {} offset={} key={}: {}",
                            sourceTopic, rec.offset(), rec.key(), e.getMessage());
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sourceTopic", sourceTopic);
        result.put("targetTopic", targetTopic);
        result.put("replayed", replayed);
        result.put("errors", errors);
        log.warn("DLT replay complete: source={} target={} replayed={} errors={}",
            sourceTopic, targetTopic, replayed, errors);
        return ResponseEntity.ok(ApiResponse.ok("Replay complete", result));
    }

    /**
     * Resurrect outbox rows that were marked {@code failedPermanent} so the
     * scheduler attempts them again on the next tick. Scoped to a single id
     * or the entire poison bucket.
     */
    @PostMapping("/outbox/retry-failed")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> retryFailedOutbox(
            @RequestParam(required = false) Long id) {

        int reset;
        if (id != null) {
            reset = outboxRepo.resetFailedPermanentById(id);
        } else {
            reset = outboxRepo.resetAllFailedPermanent();
        }
        log.warn("Outbox retry-failed: id={} reset={} rows", id, reset);
        return ResponseEntity.ok(ApiResponse.ok(
            "Outbox rows reset — scheduler will retry on next tick",
            Map.of("reset", reset)));
    }

    /**
     * Quick ops pulse. When {@code outboxFailedPermanent > 0} or a DLT depth
     * is nonzero, page on-call.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> opsHealth() {
        long failed = outboxRepo.countByFailedPermanentTrue();
        List<OutboxEvent> samples = outboxRepo.findTop200ByFailedPermanentTrueOrderByCreatedAtAsc();
        List<Map<String, Object>> sampleRows = samples.stream().limit(10).map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("topic", e.getTopic());
            m.put("aggregateKey", e.getAggregateKey());
            m.put("attempts", e.getAttempts());
            m.put("lastError", e.getLastError());
            m.put("createdAt", String.valueOf(e.getCreatedAt()));
            return m;
        }).toList();
        Map<String, Object> result = new HashMap<>();
        result.put("outboxFailedPermanent", failed);
        result.put("samples", sampleRows);
        result.put("status", failed == 0 ? "OK" : "DEGRADED");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
