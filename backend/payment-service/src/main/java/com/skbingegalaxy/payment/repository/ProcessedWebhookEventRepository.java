package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ProcessedWebhookEventRepository
        extends JpaRepository<ProcessedWebhookEvent, ProcessedWebhookEvent.Pk> {

    boolean existsByEventIdAndProvider(String eventId, String provider);

    @Modifying
    @Query("delete from ProcessedWebhookEvent e where e.receivedAt < :cutoff")
    int deleteAllByReceivedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
