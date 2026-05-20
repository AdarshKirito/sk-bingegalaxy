package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.booking.web.RequestContext;
import com.skbingegalaxy.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Central producer for all Kafka events emitted by booking-service.
 *
 * <p>Why a dedicated service instead of inlining the outbox write?
 * Before this class existed, three different call sites
 * ({@code BookingService.publishBookingEvent}, {@code BookingTransferService},
 * {@code WaitlistService}) each built their own {@link OutboxEvent} row by
 * hand. That meant every new envelope field (eventId, version, correlationId)
 * had to be wired in three places — and inevitably drifted. With this single
 * choke-point:
 *
 * <ul>
 *   <li>{@link EventEnvelope#ensureDefaults()} runs on every payload, so
 *       eventId/occurredAt/eventVersion are guaranteed never-null on the wire,
 *       letting consumers safely use eventId as an idempotency key.</li>
 *   <li>The same metadata is mirrored into outbox columns (V46) so we can
 *       query/replay/deduplicate without parsing JSON.</li>
 *   <li>Correlation id is auto-pulled from {@link RequestContext} so any
 *       request-scoped emit links back to its originating HTTP call.</li>
 *   <li>The unique index on {@code event_id} catches double-emits at the DB
 *       layer instead of producing two Kafka messages.</li>
 * </ul>
 *
 * <p><b>Transaction semantics</b>: this method <em>requires</em> an existing
 * transaction (see {@link Propagation#MANDATORY}). The point of the outbox is
 * that the row is written in the same transaction as the domain change — if
 * a caller forgot the {@code @Transactional} they would silently lose the
 * atomicity guarantee, so we fail loudly instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist an envelope-typed event to the transactional outbox.
     *
     * @param topic        Kafka topic (use {@code KafkaTopics.*} constants)
     * @param aggregateKey Kafka partition key (e.g. bookingRef). Capped at 30
     *                     chars by the column definition.
     * @param payload      concrete event extending {@link EventEnvelope}.
     *                     Mutated in-place: {@code ensureDefaults()} fills
     *                     missing eventId/occurredAt/version.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends EventEnvelope> void publish(String topic, String aggregateKey, T payload) {
        if (payload.getEventType() == null) {
            payload.setEventType(topic);
        }
        if (payload.getCorrelationId() == null) {
            String corr = RequestContext.currentCorrelationId();
            payload.setCorrelationId(corr != null ? corr : UUID.randomUUID().toString());
        }
        payload.ensureDefaults();

        try {
            OutboxEvent row = OutboxEvent.builder()
                .topic(topic)
                .aggregateKey(aggregateKey)
                .payload(objectMapper.writeValueAsString(payload))
                .eventId(payload.getEventId())
                .eventType(payload.getEventType())
                .eventVersion(payload.getEventVersion())
                .occurredAt(LocalDateTime.ofInstant(payload.getOccurredAt(), ZoneOffset.UTC))
                .correlationId(payload.getCorrelationId())
                .build();
            outboxEventRepository.save(row);
            log.debug("outbox.publish topic={} key={} eventId={} version={} corr={}",
                topic, aggregateKey, payload.getEventId(), payload.getEventVersion(),
                payload.getCorrelationId());
        } catch (Exception e) {
            // Note: this rolls back the surrounding domain transaction by design.
            log.error("Failed to write outbox row topic={} key={} eventId={}",
                topic, aggregateKey, payload.getEventId(), e);
            throw new IllegalStateException(
                "Failed to persist outbox event for topic " + topic + " and key " + aggregateKey, e);
        }
    }
}
