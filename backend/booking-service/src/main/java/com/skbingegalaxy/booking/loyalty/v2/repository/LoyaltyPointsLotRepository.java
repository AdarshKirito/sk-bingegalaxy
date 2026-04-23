package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyPointsLotRepository extends JpaRepository<LoyaltyPointsLot, Long> {

    /**
     * FIFO-ordered eligible lots for redemption / expiry.
     * Caller iterates and decrements {@code remainingPoints}.
     */
    @Query("""
        SELECT l FROM LoyaltyPointsLot l
         WHERE l.walletId        = :walletId
           AND l.remainingPoints > 0
         ORDER BY l.earnedAt ASC, l.id ASC
        """)
    List<LoyaltyPointsLot> findFifoOpen(@Param("walletId") Long walletId);

    /**
     * Lots past their {@code expiresAt} that still have remaining points.
     * Hot path for the nightly expiry job.
     */
    @Query("""
        SELECT l FROM LoyaltyPointsLot l
         WHERE l.remainingPoints > 0
           AND l.expiresAt <= :cutoff
         ORDER BY l.expiresAt ASC
        """)
    List<LoyaltyPointsLot> findExpiringLots(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Sum of currently-redeemable points for a wallet. Sanity check
     * against {@code LoyaltyPointsWallet.currentBalance} during reconcile.
     */
    @Query("""
        SELECT COALESCE(SUM(l.remainingPoints), 0)
          FROM LoyaltyPointsLot l
         WHERE l.walletId = :walletId
        """)
    long sumRemaining(@Param("walletId") Long walletId);
}
