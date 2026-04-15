package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CancellationTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CancellationTierRepository extends JpaRepository<CancellationTier, Long> {

    /** Get all tiers for a binge, sorted descending by hoursBeforeStart (most generous first). */
    List<CancellationTier> findByBingeIdOrderByHoursBeforeStartDesc(Long bingeId);

    void deleteByBingeId(Long bingeId);

    boolean existsByBingeId(Long bingeId);
}
