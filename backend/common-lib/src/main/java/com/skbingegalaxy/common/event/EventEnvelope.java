package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Common metadata header carried on every Kafka event payload across services.
 *
 * <h3>Why an envelope?</h3>
 * The first generation of {@code BookingEvent}/{@code PaymentEvent}/etc. was a
 * flat POJO whose only "type" signal was the Kafka topic. That was fine while
 * each topic carried exactly one event shape, but it left us with no way to:
 *
 * <ul>
 *   <li>De-duplicate retried deliveries on the consumer side
 *       ({@link #eventId});</li>
 *   <li>Evolve a payload schema without an all-services lockstep deploy
 *       ({@link #eventVersion});</li>
 *   <li>Correlate a fan-out across services back to the originating HTTP
 *       request ({@link #correlationId});</li>
 *   <li>Tell a producer-side timestamp apart from a domain timestamp like
 *       {@code paidAt} or {@code bookingDate} ({@link #occurredAt});</li>
 *   <li>Distinguish two semantically-different events that happen to share a
 *       topic ({@link #eventType}).</li>
 * </ul>
 *
 * <h3>Backwards compatibility</h3>
 * Every concrete event POJO that extends this class inherits these fields as
 * null/default unless the producer explicitly sets them. Old payloads still
 * deserialize — Jackson will populate the envelope fields as {@code null}, and
 * {@link #ensureDefaults()} can be called by consumers that need a fallback
 * eventId for de-dup. Old consumers that don't know about the envelope simply
 * ignore the unknown JSON properties (we already configure
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} in the shared Jackson config).
 *
 * <h3>Field semantics</h3>
 * The envelope is intentionally minimal. Aggregate identifiers (e.g.
 * {@code bookingRef}) stay on the concrete event because they are part of the
 * domain payload and are also used as the Kafka partition key.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class EventEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Globally-unique idempotency key. Consumers MAY use it to dedupe. */
    private String eventId;

    /**
     * Event-shape version. Bump when fields are added/removed in a way
     * consumers must adapt to. Existing producers default to 1.
     */
    private Integer eventVersion;

    /**
     * Domain event type discriminator (e.g. {@code "booking.confirmed"}). When
     * a topic carries a single event shape this duplicates the topic name; when
     * we ever multiplex events on a topic the consumer can branch on this.
     */
    private String eventType;

    /**
     * Producer-side timestamp at the moment the outbox row was written. NOT
     * the domain timestamp (e.g. for a payment event {@code paidAt} is the
     * domain time, this is the emit time).
     */
    private Instant occurredAt;

    /**
     * Distributed-trace correlation id. Threaded from the originating HTTP
     * request via {@code RequestContext}/MDC. May be null for system-emitted
     * events with no inbound HTTP origin (e.g. scheduler ticks).
     */
    private String correlationId;

    /**
     * Convenience: populate {@link #eventId} and {@link #occurredAt} with
     * sensible defaults when the producer didn't set them. Idempotent — never
     * overwrites a non-null field.
     */
    public void ensureDefaults() {
        if (eventId == null || eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (eventVersion == null) {
            eventVersion = 1;
        }
    }
}
