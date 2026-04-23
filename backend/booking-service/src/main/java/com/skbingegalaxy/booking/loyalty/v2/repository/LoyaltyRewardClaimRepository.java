package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyRewardClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyRewardClaimRepository extends JpaRepository<LoyaltyRewardClaim, Long> {

    Page<LoyaltyRewardClaim> findByMembershipIdOrderByClaimedAtDesc(Long membershipId, Pageable pageable);

    List<LoyaltyRewardClaim> findByMembershipIdAndStatus(Long membershipId, String status);

    /** Reserved claims past expiry — swept by the expiry job. */
    @Query("""
        SELECT c FROM LoyaltyRewardClaim c
         WHERE c.status = 'RESERVED'
           AND c.expiresAt IS NOT NULL
           AND c.expiresAt <= :cutoff
        """)
    List<LoyaltyRewardClaim> findExpiring(@Param("cutoff") LocalDateTime cutoff);
}
