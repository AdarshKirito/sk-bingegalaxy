package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Pending batch: unsent events that haven't been marked as permanent failures.
     * Poisoned events (failedPermanent=true) are excluded so they don't head-of-line
     * block subsequent events on every tick.
     */
    List<OutboxEvent> findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc();
}
