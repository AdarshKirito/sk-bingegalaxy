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

    /** Recovery console: what still needs attention, oldest first. */
    List<ReservationInboxEntry> findByStatusInOrderByReceivedAtAsc(
            Collection<ReservationInboxEntry.Status> statuses);

    Page<ReservationInboxEntry> findByConnectionIdInOrderByReceivedAtDesc(
            Collection<Long> connectionIds, Pageable pageable);

    Optional<ReservationInboxEntry> findByBookingRef(String bookingRef);

    long countByConnectionIdInAndStatus(
            Collection<Long> connectionIds, ReservationInboxEntry.Status status);
}
