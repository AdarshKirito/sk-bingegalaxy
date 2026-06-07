package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    @Query(value = """
        SELECT * FROM outbox_event
        WHERE sent = false AND failed_permanent = false
        ORDER BY created_at ASC
        LIMIT 100
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingBatchWithLock();
}
