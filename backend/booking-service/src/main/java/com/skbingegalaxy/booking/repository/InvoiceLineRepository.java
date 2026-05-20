package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {
    List<InvoiceLine> findByInvoiceIdOrderBySortOrderAscIdAsc(Long invoiceId);
}
