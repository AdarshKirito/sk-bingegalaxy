package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.LoyaltyTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    Page<LoyaltyTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    List<LoyaltyTransaction> findByBookingRef(String bookingRef);

    boolean existsByAccountIdAndBookingRefAndType(Long accountId, String bookingRef, String type);

    @Query("SELECT t FROM LoyaltyTransaction t WHERE t.type = 'EARN' AND t.expiresAt IS NOT NULL AND t.expiresAt < :now AND t.points > 0")
    List<LoyaltyTransaction> findExpiredEarnTransactions(LocalDateTime now);

    @Query("SELECT COALESCE(SUM(t.points), 0) FROM LoyaltyTransaction t WHERE t.accountId = :accountId AND t.bookingRef = :bookingRef AND t.type = 'EARN'")
    long sumEarnedPointsForBooking(Long accountId, String bookingRef);
}
