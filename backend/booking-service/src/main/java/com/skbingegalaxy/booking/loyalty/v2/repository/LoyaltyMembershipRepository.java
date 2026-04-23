package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoyaltyMembershipRepository extends JpaRepository<LoyaltyMembership, Long> {

    Optional<LoyaltyMembership> findByProgramIdAndCustomerId(Long programId, Long customerId);

    Optional<LoyaltyMembership> findByMemberNumber(String memberNumber);

    boolean existsByMemberNumber(String memberNumber);

    List<LoyaltyMembership> findByProgramIdAndCustomerIdIn(Long programId, List<Long> customerIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM LoyaltyMembership m WHERE m.id = :id")
    Optional<LoyaltyMembership> findByIdForUpdate(@Param("id") Long id);

    /**
     * Memberships whose tier window is about to (or just did) expire.
     * Drives the annual rollover / demotion job.
     */
    @Query("""
        SELECT m FROM LoyaltyMembership m
         WHERE m.programId = :programId
           AND m.tierEffectiveUntil IS NOT NULL
           AND m.tierEffectiveUntil <= :cutoff
           AND m.active = true
        """)
    List<LoyaltyMembership> findTierExpiringBy(@Param("programId") Long programId,
                                               @Param("cutoff") LocalDateTime cutoff);
}
