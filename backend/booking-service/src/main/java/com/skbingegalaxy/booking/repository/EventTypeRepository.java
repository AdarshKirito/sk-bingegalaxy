package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventTypeRepository extends JpaRepository<EventType, Long> {
    List<EventType> findByActiveTrue();
    List<EventType> findByBingeIdAndActiveTrue(Long bingeId);
    List<EventType> findByBingeId(Long bingeId);

    @Query("SELECT e FROM EventType e WHERE (e.bingeId = :bingeId OR e.bingeId IS NULL) AND e.active = true")
    List<EventType> findByBingeIdOrGlobalAndActiveTrue(@Param("bingeId") Long bingeId);

    @Query("SELECT e FROM EventType e WHERE e.bingeId = :bingeId OR e.bingeId IS NULL")
    List<EventType> findByBingeIdOrGlobal(@Param("bingeId") Long bingeId);
}
