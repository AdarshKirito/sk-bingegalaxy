package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {
    Optional<CreditNote> findByCreditNoteNumber(String creditNoteNumber);
    List<CreditNote> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
    List<CreditNote> findByBookingRefOrderByCreatedAtDesc(String bookingRef);
}
