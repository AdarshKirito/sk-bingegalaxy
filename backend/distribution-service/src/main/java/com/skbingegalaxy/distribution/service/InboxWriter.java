package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import com.skbingegalaxy.distribution.repository.ReservationInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The two database operations that make duplicate delivery survivable, each in its own
 * transaction.
 *
 * <p><b>Why this is a separate bean at all.</b> {@link ReservationInboxService#receive}
 * relies on the {@code uk_inbox_message} unique index to detect a redelivered message —
 * deliberately, because a read-then-write check has a time-of-check race that the index
 * does not. It then caught the constraint violation and looked up the original row to
 * return it.
 *
 * <p>That recovery could never work from inside the same transaction. A constraint
 * violation poisons the Hibernate session and marks the transaction rollback-only; the
 * next query on it fails with {@code AssertionFailure: null id ... (don't flush the
 * Session after an exception occurs)}. So the path that existed specifically to make
 * redelivery harmless answered <b>HTTP 500</b> instead — and under at-least-once
 * delivery redelivery is not an edge case, it is the normal behaviour of every provider.
 * A reseller receiving 500 retries harder, and OCTO clients treat 5xx as "the supplier is
 * down".
 *
 * <p>Splitting the two operations into separate {@code REQUIRES_NEW} transactions is what
 * makes the recovery legal: the failed insert rolls back alone, and the lookup runs on a
 * session that never saw the violation. Self-invocation could not achieve this — a
 * private method call bypasses the proxy, so the second transaction would never start.
 */
@Component
@RequiredArgsConstructor
class InboxWriter {

    private final ReservationInboxRepository inboxRepository;

    /**
     * Insert, flushing immediately.
     *
     * <p>{@code saveAndFlush} rather than {@code save} so a unique-index violation
     * surfaces here, inside the transaction that can be discarded, instead of at commit
     * time where the caller can no longer tell which statement failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationInboxEntry insert(ReservationInboxEntry entry) {
        return inboxRepository.saveAndFlush(entry);
    }

    /**
     * The row a redelivered message collided with.
     *
     * <p>A fresh transaction, so it is unaffected by the insert that just failed. The
     * most recent match wins: the unique index keys on
     * {@code (connection_id, external_ref, message_type, sequence)}, so several rows can
     * legitimately share the first three when a provider supplies sequences.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Optional<ReservationInboxEntry> findExisting(Long connectionId, String externalRef,
                                                 ReservationInboxEntry.MessageType messageType) {
        return inboxRepository
            .findByConnectionIdAndExternalRefOrderByIdAsc(connectionId, externalRef)
            .stream()
            .filter(e -> e.getMessageType() == messageType)
            .reduce((first, second) -> second);
    }

    /**
     * The row this exact message would collide with, if one already exists.
     *
     * <p>A fast path taken BEFORE attempting the insert. The unique index remains the
     * guarantee — this read cannot close the time-of-check window and is not trying to —
     * but it means the ordinary redelivery no longer reaches the constraint at all.
     *
     * <p>That matters operationally rather than functionally: the violation was already
     * handled correctly, but Hibernate logs every one at ERROR with a SQLState, so a
     * provider's normal retry behaviour filled the log with what looks like a database
     * incident. Alerting that fires on routine traffic is alerting people turn off.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Optional<ReservationInboxEntry> findCollision(Long connectionId, String externalRef,
                                                  ReservationInboxEntry.MessageType messageType,
                                                  Long sequence) {
        return inboxRepository
            .findCollisions(connectionId, externalRef, messageType, sequence)
            .stream().findFirst();
    }
}
