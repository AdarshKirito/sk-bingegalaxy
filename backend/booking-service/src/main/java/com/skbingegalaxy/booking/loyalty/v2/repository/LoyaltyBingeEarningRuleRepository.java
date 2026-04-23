package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeEarningRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyBingeEarningRuleRepository extends JpaRepository<LoyaltyBingeEarningRule, Long> {

    /**
     * All effective earn rules for a binding at the given instant, both
     * tier-specific and universal.  Caller picks the most specific one
     * via {@code EarnRuleResolver}.
     */
    @Query("""
        SELECT r FROM LoyaltyBingeEarningRule r
         WHERE r.bindingId = :bindingId
           AND r.effectiveFrom <= :at
           AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
        """)
    List<LoyaltyBingeEarningRule> findActive(@Param("bindingId") Long bindingId,
                                             @Param("at") LocalDateTime at);
}
