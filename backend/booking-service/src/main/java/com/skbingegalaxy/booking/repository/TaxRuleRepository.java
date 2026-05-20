package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.TaxRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TaxRuleRepository extends JpaRepository<TaxRule, Long> {

    /**
     * Resolves the active tax rules for a binge: union of binge-scoped rules
     * and platform-wide (binge_id IS NULL) rules, both filtered to active.
     * Sorted by priority asc so callers can apply deterministically.
     */
    @Query("SELECT t FROM TaxRule t " +
           "WHERE t.active = true AND (t.bingeId = :bingeId OR t.bingeId IS NULL) " +
           "ORDER BY t.priority ASC, t.id ASC")
    List<TaxRule> findActiveForBinge(Long bingeId);

    List<TaxRule> findByBingeIdOrderByPriorityAscIdAsc(Long bingeId);

    @Query("SELECT t FROM TaxRule t WHERE t.bingeId IS NULL ORDER BY t.priority ASC, t.id ASC")
    List<TaxRule> findGlobalRules();
}
