package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Optional<LedgerEntry> findByEntryUuid(String entryUuid);
    List<LedgerEntry> findByBookingRefOrderByOccurredAtAsc(String bookingRef);
    List<LedgerEntry> findByInvoiceIdOrderByOccurredAtAsc(Long invoiceId);
}
