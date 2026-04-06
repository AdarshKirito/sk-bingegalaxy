package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventKey(String eventKey);
}
