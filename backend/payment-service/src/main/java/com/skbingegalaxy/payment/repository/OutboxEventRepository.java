package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Fetch the next batch of events to publish, skipping ones that have failed permanently
     * (attempts exceeded MAX_ATTEMPTS). Ordered by insertion time to preserve per-aggregate ordering.
     */
    List<OutboxEvent> findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc();
}
