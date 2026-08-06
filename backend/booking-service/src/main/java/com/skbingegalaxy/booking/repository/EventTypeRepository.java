package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventTypeRepository extends JpaRepository<EventType, Long> {
    boolean existsByBingeId(Long bingeId);

    /**
     * Does this venue have anything a customer can actually book?
     *
     * <p>Deliberately distinct from {@link #existsByBingeId}. Existence is what the
     * grace period corroborates against; visibility is what customer discovery needs.
     * A venue with thirteen switched-off event types satisfies the first and fails the
     * second, and used to do so with nothing reporting it.
     */
    boolean existsByBingeIdAndActiveTrue(Long bingeId);
    List<EventType> findByActiveTrue();
    List<EventType> findByBingeIdAndActiveTrue(Long bingeId);
    List<EventType> findByBingeId(Long bingeId);
    Optional<EventType> findByIdAndBingeId(Long id, Long bingeId);
    Optional<EventType> findByIdAndBingeIdIsNull(Long id);

    @Query("SELECT e FROM EventType e WHERE (e.bingeId = :bingeId OR e.bingeId IS NULL) AND e.active = true")
    List<EventType> findByBingeIdOrGlobalAndActiveTrue(@Param("bingeId") Long bingeId);

    @Query("SELECT e FROM EventType e WHERE e.bingeId = :bingeId OR e.bingeId IS NULL")
    List<EventType> findByBingeIdOrGlobal(@Param("bingeId") Long bingeId);

    @Query("SELECT e FROM EventType e WHERE e.id = :id AND (e.bingeId = :bingeId OR e.bingeId IS NULL)")
    Optional<EventType> findAccessibleById(@Param("id") Long id, @Param("bingeId") Long bingeId);
}
