package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
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
 * <p>Restricted to {@code ROLE_SUPER_ADMIN}: DLT replay and outbox retry are
 * platform-wide control-plane actions, not per-binge tooling — a single-venue
 * admin must not be able to replay other venues' events.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/ops")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminOpsController {

    /**
     * Must match {@code KafkaDlqProperties.dltSuffix} and the {@code TopicBuilder}
     * names in each service's {@code KafkaConfig}. One definition, so the allow-list
     * below and the topic-name derivation in {@link #replayDlt} cannot disagree.
     */
    static final String DLT_SUFFIX = "-dlt";

    /**
     * The source topics a DLT record may be replayed from — a safety allow-list.
     *
     * <p><b>Derived from the topics that actually have a DLT</b>, rather than hand-kept.
     * The previous hand-kept list was wrong in both directions: it named
     * {@code booking.confirmed-dlt}, which no service declares and Kafka therefore never
     * created, and it omitted seven DLTs that do exist — including
     * {@code notification.send-dlt} and {@code user.anonymized-dlt}, the two whose
     * records carry the most consequence when they park (an unsent notification and
     * residual PII after an erasure request). Whole classes of poisoned record had no
     * recovery route at all.
     *
     * <p>Kept in lockstep with the {@code deadLetterTopic(...)} beans in the three
     * services' {@code KafkaConfig} classes; {@code AdminOpsControllerTest} asserts the
     * suffix convention so a topic added there without a matching entry here is a test
     * failure rather than a silent gap.
     */
    static final List<String> REPLAYABLE_SOURCE_TOPIC_LIST = List.of(
        // booking-service KafkaConfig
        KafkaTopics.PAYMENT_SUCCESS + DLT_SUFFIX,
        KafkaTopics.PAYMENT_FAILED + DLT_SUFFIX,
        KafkaTopics.PAYMENT_REFUNDED + DLT_SUFFIX,
        KafkaTopics.BOOKING_CANCELLED + DLT_SUFFIX,
        KafkaTopics.BOOKING_RESCHEDULED + DLT_SUFFIX,
        KafkaTopics.BOOKING_TRANSFERRED + DLT_SUFFIX,
        KafkaTopics.USER_ANONYMIZED + DLT_SUFFIX,
        // payment-service KafkaConfig
        KafkaTopics.BOOKING_CASH_PAYMENT + DLT_SUFFIX,
        // notification-service KafkaConfig
        KafkaTopics.BOOKING_CREATED + DLT_SUFFIX,
        KafkaTopics.NOTIFICATION_SEND + DLT_SUFFIX,
        KafkaTopics.USER_REGISTERED + DLT_SUFFIX,
        KafkaTopics.PASSWORD_RESET + DLT_SUFFIX
    );

    private static final Set<String> REPLAYABLE_SOURCE_TOPICS =
        Set.copyOf(REPLAYABLE_SOURCE_TOPIC_LIST);

    /** Ceiling on one replay batch. The console must not offer more than this. */
    static final int MAX_REPLAY_BATCH = 1000;

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
        if (max <= 0 || max > MAX_REPLAY_BATCH) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                "max must be between 1 and " + MAX_REPLAY_BATCH));
        }
        String targetTopic = sourceTopic.substring(0, sourceTopic.length() - DLT_SUFFIX.length());

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
     * The replay allow-list, served to the console.
     *
     * <p>The console used to hard-code its own list of topic names. Not one of the five
     * it offered was in the server's allow-list, so the default selection — and every
     * other option in the dropdown — was rejected with a 400. Serving the list means the
     * two can no longer drift: there is only one of them.
     */
    @GetMapping("/replayable-topics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replayableTopics() {
        Map<String, Object> body = new HashMap<>();
        body.put("topics", REPLAYABLE_SOURCE_TOPIC_LIST);
        body.put("maxReplayBatch", MAX_REPLAY_BATCH);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * Quick ops pulse: poisoned outbox rows plus the depth of every replayable DLT.
     *
     * <p><b>The DLT depths are measured, not assumed.</b> This endpoint previously
     * returned only outbox counters while the console rendered "DLT total messages"
     * from a {@code dltCounts} field the server never sent. It therefore read zero
     * always, and the console's "All clear" state — which required both counters to be
     * zero — was reachable with a DLT full of poisoned records.
     *
     * <p>When the broker cannot be reached, {@code dltMeasured} is false and the status
     * is {@code UNKNOWN} rather than {@code OK}. An ops console that cannot see is
     * required to say so; reporting health it did not measure is the failure this
     * endpoint exists to prevent.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> opsHealth() {
        long failed = outboxRepo.countByFailedPermanentTrue();
        List<OutboxEvent> samples = outboxRepo.findTop200ByFailedPermanentTrueOrderByCreatedAtAsc();
        List<Map<String, Object>> poisonRows = samples.stream().limit(50).map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("topic", e.getTopic());
            m.put("aggregateKey", e.getAggregateKey());
            m.put("attempts", e.getAttempts());
            m.put("lastError", e.getLastError());
            m.put("createdAt", String.valueOf(e.getCreatedAt()));
            return m;
        }).toList();

        DltDepths depths = cachedDltDepths();

        String status;
        if (!depths.measured()) {
            status = "UNKNOWN";
        } else if (failed > 0 || depths.total() > 0) {
            status = "DEGRADED";
        } else {
            status = "OK";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("outboxFailedPermanent", failed);
        result.put("poisonRows", poisonRows);
        result.put("dltCounts", depths.counts());
        result.put("dltTotal", depths.total());
        result.put("dltMeasured", depths.measured());
        result.put("replayableTopics", REPLAYABLE_SOURCE_TOPIC_LIST);
        result.put("maxReplayBatch", MAX_REPLAY_BATCH);
        result.put("status", status);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Undelivered record count per replayable DLT.
     *
     * <p>{@code end - beginning} rather than a consumer-group lag: a DLT has no
     * consumer, so lag is undefined and every record in it is by definition unhandled.
     *
     * @param measured false when the broker could not be reached at all — the caller
     *                 must not present the zeroes in {@code counts} as good news.
     */
    private record DltDepths(Map<String, Long> counts, long total, boolean measured) {}

    /**
     * Last measurement, with the instant it was taken.
     *
     * <p>The console polls {@code /health} every 30 seconds, per open tab. Opening a
     * Kafka consumer per request would spend three broker round trips and a thread pool
     * on every one of them, and a slow broker would hold a Tomcat worker for up to the
     * API timeout — the kind of monitoring that becomes the outage. A short TTL makes
     * the cost independent of how many operators are watching.
     */
    private final java.util.concurrent.atomic.AtomicReference<CachedDepths> depthCache =
        new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Single-flight: concurrent callers reuse the in-flight result instead of piling on. */
    private final java.util.concurrent.locks.ReentrantLock depthProbeLock =
        new java.util.concurrent.locks.ReentrantLock();

    private record CachedDepths(DltDepths depths, long takenAtMillis) {}

    /** Comfortably under the console's 30-second refresh, so a manual refresh still re-reads. */
    private static final long DEPTH_CACHE_TTL_MILLIS = 15_000L;

    private DltDepths cachedDltDepths() {
        CachedDepths cached = depthCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.takenAtMillis() < DEPTH_CACHE_TTL_MILLIS) {
            return cached.depths();
        }
        // Only one caller probes. The others take the previous value rather than queue
        // behind a broker that may be exactly what is unhealthy — and if there is no
        // previous value, they report UNMEASURED rather than block.
        if (!depthProbeLock.tryLock()) {
            return cached != null ? cached.depths() : new DltDepths(Map.of(), 0L, false);
        }
        try {
            CachedDepths again = depthCache.get();
            if (again != null && System.currentTimeMillis() - again.takenAtMillis() < DEPTH_CACHE_TTL_MILLIS) {
                return again.depths();
            }
            DltDepths fresh = measureDltDepths();
            depthCache.set(new CachedDepths(fresh, System.currentTimeMillis()));
            return fresh;
        } finally {
            depthProbeLock.unlock();
        }
    }

    private DltDepths measureDltDepths() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Never joins a group: this only reads offset metadata, so a group id would
        // create a phantom member and (worse) a rebalance on every health poll.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-depth-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // The console polls this every 30s. A broker outage must fail fast rather than
        // hold an HTTP worker for the default two minutes.
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);

        Map<String, Long> counts = new java.util.TreeMap<>();
        long total = 0;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            // One metadata round trip for every topic, then two batched offset calls —
            // rather than three calls per topic.
            Map<String, List<org.apache.kafka.common.PartitionInfo>> topics = consumer.listTopics();

            List<org.apache.kafka.common.TopicPartition> partitions = new java.util.ArrayList<>();
            for (String topic : REPLAYABLE_SOURCE_TOPIC_LIST) {
                List<org.apache.kafka.common.PartitionInfo> infos = topics.get(topic);
                // A DLT with no records has never been auto-created (auto-create is off
                // in production). Absent is genuinely zero, so report it as zero rather
                // than hiding the topic — an operator needs to see the full set.
                if (infos == null || infos.isEmpty()) {
                    counts.put(topic, 0L);
                    continue;
                }
                infos.forEach(i -> partitions.add(
                    new org.apache.kafka.common.TopicPartition(i.topic(), i.partition())));
            }

            if (!partitions.isEmpty()) {
                Map<org.apache.kafka.common.TopicPartition, Long> begin =
                    consumer.beginningOffsets(partitions);
                Map<org.apache.kafka.common.TopicPartition, Long> end =
                    consumer.endOffsets(partitions);
                for (org.apache.kafka.common.TopicPartition tp : partitions) {
                    long depth = Math.max(0,
                        end.getOrDefault(tp, 0L) - begin.getOrDefault(tp, 0L));
                    counts.merge(tp.topic(), depth, Long::sum);
                    total += depth;
                }
            }
            return new DltDepths(counts, total, true);
        } catch (Exception e) {
            // Reported as unmeasured, not as zero. The distinction is the whole point:
            // "no poisoned records" and "we could not look" must never render alike.
            log.warn("DLT depth probe failed ({}) — reporting status UNKNOWN", e.toString());
            return new DltDepths(Map.of(), 0L, false);
        }
    }
}
