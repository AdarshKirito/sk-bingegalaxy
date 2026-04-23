package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeBinding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoyaltyBingeBindingRepository extends JpaRepository<LoyaltyBingeBinding, Long> {

    Optional<LoyaltyBingeBinding> findByProgramIdAndBingeId(Long programId, Long bingeId);

    @Query("""
        SELECT b FROM LoyaltyBingeBinding b
         WHERE b.programId = :programId
           AND b.bingeId   = :bingeId
           AND b.status   IN ('ENABLED', 'ENABLED_LEGACY')
        """)
    Optional<LoyaltyBingeBinding> findActive(@Param("programId") Long programId,
                                             @Param("bingeId") Long bingeId);

    @Query("""
        SELECT b FROM LoyaltyBingeBinding b
         WHERE b.programId = :programId
           AND b.status   IN ('ENABLED', 'ENABLED_LEGACY')
        """)
    List<LoyaltyBingeBinding> findAllActive(@Param("programId") Long programId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LoyaltyBingeBinding b WHERE b.id = :id")
    Optional<LoyaltyBingeBinding> findByIdForUpdate(@Param("id") Long id);

    List<LoyaltyBingeBinding> findByProgramIdAndBingeIdIn(Long programId, List<Long> bingeIds);
}
