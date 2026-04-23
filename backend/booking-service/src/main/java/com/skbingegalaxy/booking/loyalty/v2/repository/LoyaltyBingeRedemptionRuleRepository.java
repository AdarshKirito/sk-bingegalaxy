package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeRedemptionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoyaltyBingeRedemptionRuleRepository extends JpaRepository<LoyaltyBingeRedemptionRule, Long> {

    @Query("""
        SELECT r FROM LoyaltyBingeRedemptionRule r
         WHERE r.bindingId = :bindingId
           AND r.effectiveFrom <= :at
           AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
         ORDER BY r.effectiveFrom DESC
        """)
    Optional<LoyaltyBingeRedemptionRule> findActive(@Param("bindingId") Long bindingId,
                                                   @Param("at") LocalDateTime at);
}
