package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPerkCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoyaltyPerkCatalogRepository extends JpaRepository<LoyaltyPerkCatalog, Long> {

    Optional<LoyaltyPerkCatalog> findByProgramIdAndCode(Long programId, String code);

    @Query("""
        SELECT p FROM LoyaltyPerkCatalog p
         WHERE p.programId = :programId
           AND p.active = true
           AND p.effectiveFrom <= :at
           AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
        """)
    List<LoyaltyPerkCatalog> findActiveByProgram(@Param("programId") Long programId,
                                                 @Param("at") LocalDateTime at);
}
