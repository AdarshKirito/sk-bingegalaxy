package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyQualificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyQualificationEventRepository extends JpaRepository<LoyaltyQualificationEvent, Long> {

    /**
     * Sum of active (in-window) qualification credits for a membership.
     * Drives TierEngine — MUST use an indexed range scan, not a full
     * table aggregate.
     */
    @Query("""
        SELECT COALESCE(SUM(q.qualificationCredits), 0)
          FROM LoyaltyQualificationEvent q
         WHERE q.membershipId = :membershipId
           AND q.expiresFromWindowAt > :now
        """)
    long sumActiveCredits(@Param("membershipId") Long membershipId,
                          @Param("now") LocalDateTime now);

    /**
     * Lifetime credits (all time, regardless of window).  Used for
     * lifetime-tier qualification (e.g. Lifetime Platinum at 250k).
     */
    @Query("""
        SELECT COALESCE(SUM(q.qualificationCredits), 0)
          FROM LoyaltyQualificationEvent q
         WHERE q.membershipId = :membershipId
        """)
    long sumLifetimeCredits(@Param("membershipId") Long membershipId);

    List<LoyaltyQualificationEvent> findByMembershipIdAndBookingRef(Long membershipId, String bookingRef);
}
