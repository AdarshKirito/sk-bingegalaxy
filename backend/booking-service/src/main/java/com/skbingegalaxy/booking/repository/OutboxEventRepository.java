package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100BySentFalseOrderByCreatedAtAsc();
}
