package com.skbingegalaxy.booking.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * In-process Spring {@link org.springframework.context.ApplicationEvent}
 * fired by {@code BookingService} when a booking transitions into the
 * {@code COMPLETED} state.
 *
 * <p><b>Why a Spring event, not a Kafka topic?</b>  These events drive
 * IN-PROCESS listeners (loyalty earn, audit logging) that MUST be in
 * the same JVM and the same transactional boundary as the booking
 * change.  Using Spring's {@code @TransactionalEventListener
 * (phase = AFTER_COMMIT)} guarantees that listeners fire only after
 * the booking commit succeeds — a dropped database transaction
 * becomes a dropped loyalty event, preventing phantom point awards.
 *
 * <p>Out-of-process integrations (external email / CRM) continue to
 * use the existing Kafka outbox pattern.
 *
 * <p>Immutable record — listeners cannot tamper with the event payload.
 */
public record BookingCompletedEvent(
        Long bookingId,
        String bookingRef,
        Long customerId,
        Long bingeId,
        Long tenantId,
        BigDecimal totalAmount,
        LocalDateTime completedAt
) { }
