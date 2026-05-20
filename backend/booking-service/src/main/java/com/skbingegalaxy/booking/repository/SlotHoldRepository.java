package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.SlotHold;
import com.skbingegalaxy.booking.entity.SlotHold.SlotHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotHoldRepository extends JpaRepository<SlotHold, Long> {

    Optional<SlotHold> findByHoldToken(String token);

    /** All ACTIVE non-expired holds for a binge on a date — used for conflict / capacity checks. */
    @Query("SELECT h FROM SlotHold h WHERE h.bingeId = :bid AND h.bookingDate = :date " +
           "AND h.status = 'ACTIVE' AND h.expiresAt > :now")
    List<SlotHold> findLiveHoldsByBingeAndDate(@Param("bid") Long bingeId,
                                               @Param("date") LocalDate date,
                                               @Param("now") LocalDateTime now);

    /** Number of currently-live holds a customer has on this binge. */
    @Query("SELECT COUNT(h) FROM SlotHold h WHERE h.customerId = :cid AND h.bingeId = :bid " +
           "AND h.status = 'ACTIVE' AND h.expiresAt > :now")
    long countLiveByCustomer(@Param("cid") Long customerId,
                             @Param("bid") Long bingeId,
                             @Param("now") LocalDateTime now);

    /** Stale ACTIVE holds whose TTL elapsed — picked up by the expiry scheduler. */
    @Query("SELECT h FROM SlotHold h WHERE h.status = 'ACTIVE' AND h.expiresAt <= :cutoff")
    List<SlotHold> findExpiredActiveHolds(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Recovery queue: ACTIVE holds whose TTL elapsed before the expiry
     * scheduler caught them. The scheduler should always reach 0; if rows
     * accumulate here the scheduler is dead or slow and on-call should page.
     */
    @Query("SELECT h FROM SlotHold h WHERE h.status = 'ACTIVE' " +
           "AND h.expiresAt <= :cutoff ORDER BY h.expiresAt ASC")
    org.springframework.data.domain.Page<SlotHold> findExpiredNotReleased(
            @Param("cutoff") LocalDateTime cutoff,
            org.springframework.data.domain.Pageable pageable);


    /** Active holds for the current binge — admin "active holds" view. */
    List<SlotHold> findByBingeIdAndStatusOrderByExpiresAtAsc(Long bingeId, SlotHoldStatus status);

    /** Customer self-view: all live holds (ACTIVE + not expired). */
    @Query("SELECT h FROM SlotHold h WHERE h.customerId = :cid AND h.status = 'ACTIVE' " +
           "AND h.expiresAt > :now ORDER BY h.expiresAt ASC")
    List<SlotHold> findLiveByCustomer(@Param("cid") Long customerId, @Param("now") LocalDateTime now);

    /** Cleanup very old terminal rows (expired / released / converted older than the retention window). */
    @Modifying
    @Query("DELETE FROM SlotHold h WHERE h.status <> 'ACTIVE' AND h.updatedAt < :cutoff")
    int deleteOldTerminalHolds(@Param("cutoff") LocalDateTime cutoff);
}
