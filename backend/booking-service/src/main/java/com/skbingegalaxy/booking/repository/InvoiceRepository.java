package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
    Optional<Invoice> findFirstByBookingRefOrderByIdDesc(String bookingRef);
    List<Invoice> findByCustomerIdOrderByIssuedAtDesc(Long customerId);
    List<Invoice> findByBingeIdOrderByIssuedAtDesc(Long bingeId);
}
