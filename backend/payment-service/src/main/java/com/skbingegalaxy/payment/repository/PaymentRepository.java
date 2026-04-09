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

    List<Payment> findByBookingRefAndBingeIdOrderByCreatedAtDesc(String bookingRef, Long bingeId);

    List<Payment> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Payment> findByCustomerIdAndBingeIdOrderByCreatedAtDesc(Long customerId, Long bingeId);

    List<Payment> findByBookingRefAndStatus(String bookingRef, PaymentStatus status);

    List<Payment> findByBookingRefAndStatusAndBingeId(String bookingRef, PaymentStatus status, Long bingeId);

    /**
     * Find the most recent non-terminal (INITIATED/PENDING) payment for a booking,
     * used for idempotent re-initiation.
     */
    Optional<Payment> findFirstByBookingRefAndStatusOrderByCreatedAtDesc(
            String bookingRef, PaymentStatus status);

        Optional<Payment> findFirstByBookingRefAndStatusAndBingeIdOrderByCreatedAtDesc(
            String bookingRef, PaymentStatus status, Long bingeId);

    long countByStatus(PaymentStatus status);

        long countByStatusAndBingeId(PaymentStatus status, Long bingeId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'SUCCESS'")
    BigDecimal getTotalSuccessfulPayments();

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'SUCCESS' AND p.bingeId = :bingeId")
        BigDecimal getTotalSuccessfulPaymentsByBingeId(@Param("bingeId") Long bingeId);

    /**
     * Acquires a pessimistic write lock on the payment row to prevent concurrent
     * over-refund from parallel requests.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Check for a recent duplicate admin-added payment (same booking, method, amount, SUCCESS status)
     * created within the last few seconds — used as an idempotency guard for addPayment().
     */
    @Query("SELECT p FROM Payment p WHERE p.bookingRef = :bookingRef AND p.paymentMethod = :method " +
           "AND p.amount = :amount AND p.status = 'SUCCESS' AND p.createdAt >= :since ORDER BY p.createdAt DESC")
    List<Payment> findRecentDuplicates(@Param("bookingRef") String bookingRef,
                                       @Param("method") com.skbingegalaxy.common.enums.PaymentMethod method,
                                       @Param("amount") BigDecimal amount,
                                       @Param("since") java.time.LocalDateTime since);

        @Query("SELECT p FROM Payment p WHERE p.bookingRef = :bookingRef AND p.paymentMethod = :method " +
            "AND p.amount = :amount AND p.status = 'SUCCESS' AND p.bingeId = :bingeId " +
            "AND p.createdAt >= :since ORDER BY p.createdAt DESC")
        List<Payment> findRecentDuplicatesByBingeId(@Param("bookingRef") String bookingRef,
                                  @Param("method") com.skbingegalaxy.common.enums.PaymentMethod method,
                                  @Param("amount") BigDecimal amount,
                                  @Param("bingeId") Long bingeId,
                                  @Param("since") java.time.LocalDateTime since);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.bookingRef = :bookingRef AND p.status = 'SUCCESS'")
    BigDecimal sumSuccessfulPaymentsByBookingRef(@Param("bookingRef") String bookingRef);

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.bookingRef = :bookingRef AND p.status = 'SUCCESS' AND p.bingeId = :bingeId")
        BigDecimal sumSuccessfulPaymentsByBookingRefAndBingeId(@Param("bookingRef") String bookingRef,
                                       @Param("bingeId") Long bingeId);
}
