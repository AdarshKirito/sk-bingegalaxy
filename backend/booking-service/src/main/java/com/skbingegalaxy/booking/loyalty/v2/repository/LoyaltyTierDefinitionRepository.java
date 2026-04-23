package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyTierDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoyaltyTierDefinitionRepository extends JpaRepository<LoyaltyTierDefinition, Long> {

    /**
     * Active tier ladder (effective-dated), ranked ascending — Bronze first.
     * Used by TierEngine on every recalc.
     */
    @Query("""
        SELECT t FROM LoyaltyTierDefinition t
         WHERE t.programId = :programId
           AND t.effectiveFrom <= :at
           AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
         ORDER BY t.rankOrder ASC
        """)
    List<LoyaltyTierDefinition> findActiveLadder(@Param("programId") Long programId,
                                                 @Param("at") LocalDateTime at);

    @Query("""
        SELECT t FROM LoyaltyTierDefinition t
         WHERE t.programId = :programId
           AND t.code      = :code
           AND t.effectiveFrom <= :at
           AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
        """)
    Optional<LoyaltyTierDefinition> findActiveByCode(@Param("programId") Long programId,
                                                    @Param("code") String code,
                                                    @Param("at") LocalDateTime at);
}
