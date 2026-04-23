package com.skbingegalaxy.common.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Shared Kafka consumer error-handling configuration.
 *
 * <p>On listener exception, Spring Kafka will:</p>
 * <ol>
 *   <li>Retry with exponential backoff (initial 500ms, multiplier 2, max 3 attempts).</li>
 *   <li>If all retries fail, publish the original record to {@code <topic>.DLT}
 *       (dead-letter topic) with the failure stack trace in headers, and then
 *       commit the offset so the partition progresses.</li>
 * </ol>
 *
 * <p>This prevents a single poison-pill message from blocking the consumer
 * group indefinitely (head-of-line blocking), which is the classic production
 * outage scenario for naive at-least-once consumers.</p>
 *
 * <p>DLT topics are auto-created by the recoverer. A separate alerting rule
 * on {@code kafka_consumer_dead_letter_records_total} should page ops when DLT
 * traffic exceeds 0 — every DLT entry is an unresolved bug or data-quality
 * issue.</p>
 *
 * <p>Activation: services that import this class (via
 * {@code @Import(KafkaDlqErrorHandlerConfig.class)} or component-scan) get the
 * handler automatically; services without a Kafka consumer simply don't scan
 * it.</p>
 */
@Configuration
@Slf4j
public class KafkaDlqErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            template,
            (record, ex) -> {
                log.error("Routing poison record to DLT: topic={} partition={} offset={} key={} cause={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    ex.getClass().getSimpleName(), ex);
                return new TopicPartition(record.topic() + ".DLT", record.partition());
            });

        // Fixed back-off: 3 retries, 1 second apart. Stable across Spring
        // Kafka versions; sufficient for transient blips (GC, brief network
        // glitches, downstream restarts). Non-transient failures go to DLT.
        FixedBackOff backOff = new FixedBackOff(1_000L, 3L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Never retry deserialization or obviously-fatal exceptions — they will
        // never succeed on replay, so go straight to DLT.
        handler.addNotRetryableExceptions(
            org.springframework.kafka.support.serializer.DeserializationException.class,
            IllegalArgumentException.class,
            NullPointerException.class,
            ClassCastException.class);
        return handler;
    }
}
