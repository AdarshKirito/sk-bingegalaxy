package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTransactionId(String transactionId);

    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);

    List<Payment> findByBookingRefOrderByCreatedAtDesc(String bookingRef);

    List<Payment> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<Payment> findByBookingRefAndStatus(String bookingRef, PaymentStatus status);

    /**
     * Find the most recent non-terminal (INITIATED/PENDING) payment for a booking,
     * used for idempotent re-initiation.
     */
    Optional<Payment> findFirstByBookingRefAndStatusOrderByCreatedAtDesc(
            String bookingRef, PaymentStatus status);

    long countByStatus(PaymentStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'SUCCESS'")
    BigDecimal getTotalSuccessfulPayments();

    /**
     * Acquires a pessimistic write lock on the payment row to prevent concurrent
     * over-refund from parallel requests.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
