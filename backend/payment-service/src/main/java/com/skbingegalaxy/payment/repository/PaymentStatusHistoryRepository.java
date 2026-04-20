package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {

    List<PaymentStatusHistory> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);

    List<PaymentStatusHistory> findByBookingRefOrderByCreatedAtDesc(String bookingRef);
}
