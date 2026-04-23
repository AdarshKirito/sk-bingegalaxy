package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LoyaltyPointsWalletRepository extends JpaRepository<LoyaltyPointsWallet, Long> {

    Optional<LoyaltyPointsWallet> findByMembershipId(Long membershipId);

    /**
     * Pessimistic lock acquired for the duration of an earn/redeem/expire
     * transaction — guarantees serial mutation of a single wallet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM LoyaltyPointsWallet w WHERE w.membershipId = :membershipId")
    Optional<LoyaltyPointsWallet> findByMembershipIdForUpdate(@Param("membershipId") Long membershipId);
}
