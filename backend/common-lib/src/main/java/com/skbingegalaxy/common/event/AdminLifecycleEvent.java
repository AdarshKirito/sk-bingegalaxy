package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Lifecycle event emitted by booking-service when an admin acts on a
 * non-booking resource (venue rooms, room blocks, categories). Sent to the
 * transactional outbox via {@code BookingEventPublisher} so downstream
 * services (notifications, analytics, audit log) can react asynchronously
 * without coupling to the controller.
 *
 * <p>One generic shape on purpose: we don't want a new topic + event class
 * for every micro-change in the admin surface. {@link #entityType} +
 * {@link #action} together uniquely identify what happened; remaining fields
 * are optional context.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminLifecycleEvent extends EventEnvelope {
    /** Logical entity name: ROOM, ROOM_BLOCK, EVENT_CATEGORY, ADDON_CATEGORY. */
    private String entityType;
    /** Action verb: APPROVED, REJECTED, BLOCKED, UNBLOCKED, CREATED, UPDATED, DELETED. */
    private String action;
    /** Primary key of the affected entity. */
    private Long entityId;
    /** Tenant binge id (nullable for global entities like global categories). */
    private Long bingeId;
    /** Admin user id who performed the action. */
    private Long actorAdminId;
    /** Human-readable entity name when available (e.g. room name, category name). */
    private String name;
    /** Optional reason text (e.g. rejection reason, block reason). */
    private String reason;
    /** For room blocks: window start. */
    private LocalDateTime startAt;
    /** For room blocks: window end. */
    private LocalDateTime endAt;
}
