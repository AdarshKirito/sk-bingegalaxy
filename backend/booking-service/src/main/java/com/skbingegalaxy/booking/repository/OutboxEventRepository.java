package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Pending batch: unsent events that haven't been marked as permanent failures.
     * Poisoned events (failedPermanent=true) are excluded so they don't head-of-line
     * block subsequent events on every tick.
     */
    List<OutboxEvent> findTop100BySentFalseAndFailedPermanentFalseOrderByCreatedAtAsc();

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
