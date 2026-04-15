package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.SurgePricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SurgePricingRuleRepository extends JpaRepository<SurgePricingRule, Long> {

    List<SurgePricingRule> findByBingeIdOrderByDayOfWeekAscStartMinuteAsc(Long bingeId);

    List<SurgePricingRule> findByBingeIdAndActiveTrueOrderByDayOfWeekAscStartMinuteAsc(Long bingeId);

    Optional<SurgePricingRule> findByIdAndBingeId(Long id, Long bingeId);

    @Query("SELECT r FROM SurgePricingRule r WHERE r.bingeId = :bid AND r.active = true " +
           "AND (r.dayOfWeek IS NULL OR r.dayOfWeek = :dow) " +
           "AND r.startMinute <= :minute AND r.endMinute > :minute")
    List<SurgePricingRule> findMatchingRules(@Param("bid") Long bingeId,
                                             @Param("dow") int dayOfWeek,
                                             @Param("minute") int minuteOfDay);
}
