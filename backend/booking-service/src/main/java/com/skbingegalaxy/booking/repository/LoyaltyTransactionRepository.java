package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.LoyaltyTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    Page<LoyaltyTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    List<LoyaltyTransaction> findByBookingRef(String bookingRef);

    boolean existsByAccountIdAndBookingRefAndType(Long accountId, String bookingRef, String type);
}
