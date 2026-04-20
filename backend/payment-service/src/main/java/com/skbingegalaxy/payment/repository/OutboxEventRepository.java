package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100BySentFalseOrderByCreatedAtAsc();
}
