package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventTypeRepository extends JpaRepository<EventType, Long> {
    List<EventType> findByActiveTrue();
}
