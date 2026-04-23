package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeRewardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyBingeRewardItemRepository extends JpaRepository<LoyaltyBingeRewardItem, Long> {

    @Query("""
        SELECT r FROM LoyaltyBingeRewardItem r
         WHERE r.bindingId = :bindingId
           AND r.active = true
           AND r.effectiveFrom <= :at
           AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
         ORDER BY r.pointCost ASC
        """)
    List<LoyaltyBingeRewardItem> findActive(@Param("bindingId") Long bindingId,
                                            @Param("at") LocalDateTime at);
}
