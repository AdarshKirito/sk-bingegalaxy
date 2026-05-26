package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventCategoryRepository extends JpaRepository<EventCategory, Long> {

    /** Globals (super-admin owned) — for super-admin "global categories" page. */
    List<EventCategory> findByBingeIdIsNull();

    /** Per-binge custom categories — for binge admin management page. */
    List<EventCategory> findByBingeId(Long bingeId);

    /**
     * Customer-facing list: globals ∪ binge-scoped, both must be active.
     * Per-binge entries shadow globals with the same display name (the
     * caller is responsible for the dedupe).
     */
    @Query("""
        SELECT c FROM EventCategory c
        WHERE  c.active = true
          AND  (c.bingeId = :bingeId OR c.bingeId IS NULL)
        ORDER BY c.sortOrder ASC, c.name ASC
        """)
    List<EventCategory> findVisibleForBinge(@Param("bingeId") Long bingeId);

    Optional<EventCategory> findByIdAndBingeId(Long id, Long bingeId);
    Optional<EventCategory> findByIdAndBingeIdIsNull(Long id);

    boolean existsByBingeIdAndNameIgnoreCase(Long bingeId, String name);
    boolean existsByBingeIdIsNullAndNameIgnoreCase(String name);
}
