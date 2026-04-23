package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyGuestShadow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoyaltyGuestShadowRepository extends JpaRepository<LoyaltyGuestShadow, Long> {

    @Query("""
        SELECT g FROM LoyaltyGuestShadow g
         WHERE g.mergedMembershipId IS NULL
           AND (g.emailHash = :emailHash OR g.phoneHash = :phoneHash)
         ORDER BY g.firstSeenAt ASC
        """)
    List<LoyaltyGuestShadow> findUnmergedByIdentityHash(@Param("emailHash") String emailHash,
                                                       @Param("phoneHash") String phoneHash);

    Optional<LoyaltyGuestShadow> findFirstByEmailHashAndMergedMembershipIdIsNull(String emailHash);

    @Query("""
        SELECT g FROM LoyaltyGuestShadow g
         WHERE g.mergedMembershipId IS NULL
           AND g.expiresAt <= :cutoff
        """)
    List<LoyaltyGuestShadow> findExpired(@Param("cutoff") LocalDateTime cutoff);
}
