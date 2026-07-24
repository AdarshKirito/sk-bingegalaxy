package com.skbingegalaxy.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Shared Kafka consumer error-handling configuration — the single source of
 * truth for dead-letter routing across every service in the platform.
 *
 * <p><strong>Why this lives in {@code common-lib} and is defined exactly once:</strong>
 * the DLQ handler was previously copy-pasted into each service's
 * {@code KafkaConfig}. Identical logic, four places, guaranteed to drift. When
 * this centralized bean was introduced, the per-service copies collided on the
 * {@code kafkaErrorHandler} bean name and — with Spring's default
 * {@code allow-bean-definition-overriding=false} — crashed every consumer on
 * startup ({@code BeanDefinitionOverrideException}). The fix is structural, not
 * a flag: one bean here, consumed by each service's listener container factory,
 * tuned via {@link KafkaDlqProperties} from the config-server. Services that
 * need different behaviour change <em>configuration</em>, never the bean.</p>
 *
 * <p>On listener exception, Spring Kafka will:</p>
 * <ol>
 *   <li>Retry with a fixed back-off ({@code backoff-interval-ms}, up to
 *       {@code max-retries} attempts).</li>
 *   <li>If retries are exhausted, publish the original record to
 *       {@code <topic><dlt-suffix>} (default {@code <topic>-dlt}) with the
 *       failure stack trace in headers, then commit the offset so the partition
 *       progresses.</li>
 * </ol>
 *
 * <p>Suffix is {@code -dlt} (lowercase, hyphen) to match the topics pre-created
 * by each service's {@code KafkaConfig} via {@code TopicBuilder} and the
 * {@code AdminOpsController} DLT-replay allow-list. Using Spring's default
 * {@code .DLT} would silently create a second, parallel set of DLT topics that
 * never get replayed.</p>
 *
 * <p>This prevents a single poison-pill message from blocking the consumer group
 * indefinitely (head-of-line blocking) — the classic production outage for naive
 * at-least-once consumers. DLT topics auto-create via the recoverer; an alerting
 * rule on {@code kafka_consumer_dead_letter_records_total} should page ops when
 * DLT traffic exceeds 0, since every DLT entry is an unresolved bug or
 * data-quality issue.</p>
 *
 * <p><strong>Activation:</strong> services component-scan
 * {@code com.skbingegalaxy.common.config} (see each service's
 * {@code @SpringBootApplication(scanBasePackages=...)}), which picks up this
 * configuration automatically. Services without a Kafka consumer simply don't
 * scan it and pay nothing.</p>
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(KafkaDlqProperties.class)
public class KafkaDlqErrorHandlerConfig {

    private final KafkaDlqProperties properties;

    /**
     * The single, platform-wide consumer error handler. Injected by type
     * ({@link DefaultErrorHandler}) into each service's
     * {@code kafkaListenerContainerFactory}.
     *
     * <p>The template parameter is {@code KafkaTemplate<String, Object>} to match
     * the producer bean Spring Boot auto-configures in every service — the proven
     * injection point the per-service handlers used before centralization.</p>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            template,
            (record, ex) -> {
                log.error("kafka.dlt.publish topic={} partition={} offset={} key={} cause={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    ex.getClass().getSimpleName(), ex);
                return new TopicPartition(record.topic() + properties.getDltSuffix(), record.partition());
            });

        FixedBackOff backOff = new FixedBackOff(properties.getBackoffIntervalMs(), properties.getMaxRetries());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Deterministic failures will never succeed on replay, so skip retries and
        // route straight to the DLT: corrupt payloads (de/serialization) and the
        // programming-error family (bad data shape, null contracts, wrong types).
        handler.addNotRetryableExceptions(
            org.springframework.kafka.support.serializer.DeserializationException.class,
            org.apache.kafka.common.errors.SerializationException.class,
            IllegalArgumentException.class,
            NullPointerException.class,
            ClassCastException.class);
        return handler;
    }
}
