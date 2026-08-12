package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationInboxRepository extends JpaRepository<ReservationInboxEntry, Long> {

    /**
     * Highest sequence already <b>applied</b> for this external reservation.
     *
     * <p>Ordering, not just deduplication. The unique index makes a repeated CREATE
     * harmless, but says nothing about a CANCEL arriving before the MODIFY it supersedes
     * — ordinary under at-least-once delivery, and applying them in receipt order would
     * resurrect a cancelled booking. A message whose sequence does not exceed this value
     * is marked {@code SUPERSEDED} and kept.
     */
    @Query("""
           SELECT MAX(e.externalSequence) FROM ReservationInboxEntry e
           WHERE e.connectionId = :connectionId
             AND e.externalRef = :externalRef
             AND e.status = :status
           """)
    Optional<Long> findHighestSequenceWithStatus(@Param("connectionId") Long connectionId,
                                                 @Param("externalRef") String externalRef,
                                                 @Param("status") ReservationInboxEntry.Status status);

    /** Convenience for the only caller that matters: the applied high-water mark. */
    default Optional<Long> findHighestAppliedSequence(Long connectionId, String externalRef) {
        return findHighestSequenceWithStatus(
                connectionId, externalRef, ReservationInboxEntry.Status.APPLIED);
    }

    /**
     * Console lookup for a single message. Duplicate <em>prevention</em> deliberately
     * does not go through here: a read-then-write check is a time-of-check race, so
     * creation relies on the {@code uk_inbox_message} unique index and treats the
     * constraint violation as "already received".
     */
    List<ReservationInboxEntry> findByConnectionIdAndExternalRefOrderByIdAsc(
            Long connectionId, String externalRef);

    /**
     * The row a message with this exact identity would collide with.
     *
     * <p>Mirrors {@code uk_inbox_message} precisely, including its
     * {@code COALESCE(external_sequence, -1)} — which is why this is a query rather than
     * a derived method. Spring Data would render a null sequence as {@code = NULL},
     * which matches nothing in SQL, so an unsequenced redelivery would look new.
     *
     * <p><b>The sequence is part of the identity and must stay in the comparison.</b>
     * Matching on the first three columns alone would treat a genuine later
     * modification, carrying a higher sequence, as a duplicate of the earlier one — and
     * silently dropping a real modification is far worse than an occasional constraint
     * violation.
     *
     * <p>This is a fast path, not the guarantee: the unique index is still what makes
     * duplicate rejection true under concurrency. It exists so the ordinary case —
     * a provider redelivering, which at-least-once makes routine — stops being logged
     * by Hibernate as a SQL ERROR that reads like an incident.
     */
    @Query("""
           SELECT e FROM ReservationInboxEntry e
           WHERE e.connectionId = :connectionId
             AND e.externalRef = :externalRef
             AND e.messageType = :messageType
             AND ((:sequence IS NULL AND e.externalSequence IS NULL)
                  OR e.externalSequence = :sequence)
           ORDER BY e.id DESC
           """)
    List<ReservationInboxEntry> findCollisions(@Param("connectionId") Long connectionId,
                                               @Param("externalRef") String externalRef,
                                               @Param("messageType") ReservationInboxEntry.MessageType messageType,
                                               @Param("sequence") Long sequence);

    /** Recovery console: what still needs attention, oldest first. */
    List<ReservationInboxEntry> findByStatusInOrderByReceivedAtAsc(
            Collection<ReservationInboxEntry.Status> statuses);

    /**
     * The drain's batch. Bounded because the sweep runs every 30 seconds: the unpaged
     * form above reads every outstanding row on every tick, which on a backlog means
     * loading the whole queue into memory to act on a fraction of it.
     */
    Page<ReservationInboxEntry> findByStatusInOrderByReceivedAtAsc(
            Collection<ReservationInboxEntry.Status> statuses, Pageable pageable);

    /**
     * Has a cancellation for this reservation already been received?
     *
     * <p><b>Received — not applied.</b> A CANCEL that arrives before the CREATE it
     * cancels is REJECTED by booking-service ("no such reservation"), which is correct;
     * what was wrong is that the ordering high-water mark only looked at APPLIED
     * messages, so the rejected cancel left no trace the later CREATE could see. The
     * CREATE then looked like the first message for a reservation nobody had cancelled,
     * and a cancelled booking was created — a traveller who cancelled keeps a slot the
     * venue believes is sold, and nobody is expecting them.
     *
     * <p>SUPERSEDED is excluded: such a cancel was itself overtaken and is not evidence
     * of anything.
     */
    boolean existsByConnectionIdAndExternalRefAndMessageTypeAndStatusNot(
            Long connectionId, String externalRef,
            ReservationInboxEntry.MessageType messageType,
            ReservationInboxEntry.Status excludedStatus);

    /**
     * Highest sequence carried by a non-superseded message of this type — the cancel
     * tombstone's position in the provider's own ordering, so a genuinely later message
     * can still be applied over it.
     */
    @Query("""
           SELECT MAX(e.externalSequence) FROM ReservationInboxEntry e
           WHERE e.connectionId = :connectionId
             AND e.externalRef = :externalRef
             AND e.messageType = :messageType
             AND e.status <> :excludedStatus
           """)
    Optional<Long> findHighestSequenceForType(@Param("connectionId") Long connectionId,
                                              @Param("externalRef") String externalRef,
                                              @Param("messageType") ReservationInboxEntry.MessageType messageType,
                                              @Param("excludedStatus") ReservationInboxEntry.Status excludedStatus);

    Page<ReservationInboxEntry> findByConnectionIdInOrderByReceivedAtDesc(
            Collection<Long> connectionIds, Pageable pageable);

    Optional<ReservationInboxEntry> findByBookingRef(String bookingRef);

    /**
     * Terminal messages whose payload is old enough to redact and has not been redacted
     * already.
     *
     * <p>The {@code PayloadJsonNot} clause is what makes the sweep converge: without it
     * every run would re-select and re-save the same rows forever, and the log line
     * would report work that was not being done.
     */
    Page<ReservationInboxEntry> findByStatusInAndReceivedAtBeforeAndPayloadJsonNot(
            Collection<ReservationInboxEntry.Status> statuses,
            java.time.LocalDateTime receivedBefore,
            String payloadJson,
            Pageable pageable);

    long countByConnectionIdInAndStatus(
            Collection<Long> connectionIds, ReservationInboxEntry.Status status);
}
