package com.skbingegalaxy.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized tuning for the shared Kafka consumer dead-letter error handler
 * ({@link KafkaDlqErrorHandlerConfig}).
 *
 * <p>Every service inherits the same handler bean from {@code common-lib}; this
 * lets ops tune retry behaviour <em>per service</em> from the config-server
 * (e.g. give a flaky downstream more retries) without forking the code or
 * redefining the bean — which is the failure mode this whole design exists to
 * prevent. Defaults match the values the services shipped with, so binding is a
 * no-op unless explicitly overridden.</p>
 *
 * <pre>
 * skbg:
 *   kafka:
 *     dlq:
 *       dlt-suffix: "-dlt"
 *       backoff-interval-ms: 2000
 *       max-retries: 3
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "skbg.kafka.dlq")
public class KafkaDlqProperties {

    /**
     * Suffix appended to the source topic name to form the dead-letter topic
     * (e.g. {@code booking.created} -> {@code booking.created-dlt}). Must match
     * the {@code <topic>-dlt} topics pre-created by each service's KafkaConfig
     * and the AdminOps DLT-replay allow-list.
     */
    private String dltSuffix = "-dlt";

    /** Fixed back-off between retry attempts, in milliseconds. */
    private long backoffIntervalMs = 2_000L;

    /**
     * Number of retries before the record is routed to the DLT.
     * Total listener invocations = {@code maxRetries + 1}.
     */
    private long maxRetries = 3L;
}
