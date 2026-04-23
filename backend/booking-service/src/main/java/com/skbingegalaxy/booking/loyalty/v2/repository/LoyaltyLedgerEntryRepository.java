package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyLedgerEntryRepository extends JpaRepository<LoyaltyLedgerEntry, Long> {

    /** Idempotency guard — used BEFORE writing to avoid duplicate credits on retry. */
    boolean existsByWalletIdAndEntryTypeAndIdempotencyKey(Long walletId, String entryType, String idempotencyKey);

    /** Used when an idempotency hit is detected and we want to return the already-persisted entry. */
    Optional<LoyaltyLedgerEntry> findByWalletIdAndEntryTypeAndIdempotencyKey(Long walletId, String entryType, String idempotencyKey);

    Page<LoyaltyLedgerEntry> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);

    List<LoyaltyLedgerEntry> findByBookingRef(String bookingRef);

    /** Used by the booking-cancellation listener to find EARN entries on a given booking. */
    List<LoyaltyLedgerEntry> findByWalletIdAndBookingRefAndEntryType(Long walletId, String bookingRef, String entryType);
}
