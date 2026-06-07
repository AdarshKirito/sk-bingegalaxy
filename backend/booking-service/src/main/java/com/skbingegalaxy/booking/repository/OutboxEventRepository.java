package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Pending batch with advisory row-level locking (SKIP LOCKED).
     *
     * SKIP LOCKED ensures that if ShedLock's 30s lease expires and a second pod
     * starts publishing while the first is still mid-batch, the two pods work on
     * disjoint sets of rows — no duplicate Kafka publishes. ShedLock is the primary
     * guard; this is the belt-and-suspenders safety net.
     *
     * Ordered by created_at ASC to preserve per-aggregate insertion ordering within
     * each batch (Kafka key-based partitioning maintains ordering across batches).
     */
    @Query(value = """
        SELECT * FROM outbox_event
        WHERE sent = false AND failed_permanent = false
        ORDER BY created_at ASC
        LIMIT 100
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingBatchWithLock();

    long countByFailedPermanentTrue();

    List<OutboxEvent> findTop200ByFailedPermanentTrueOrderByCreatedAtAsc();

    /**
     * Ops action: resurrect failed-permanent events so the scheduler picks them up
     * on the next tick. Used after a serializer/config bug has been fixed+deployed.
     */
    @Modifying
    @Query("update OutboxEvent o set o.failedPermanent = false, o.attempts = 0, o.lastError = null " +
           "where o.failedPermanent = true")
    int resetAllFailedPermanent();

    @Modifying
    @Query("update OutboxEvent o set o.failedPermanent = false, o.attempts = 0, o.lastError = null " +
           "where o.id = :id")
    int resetFailedPermanentById(Long id);
}
