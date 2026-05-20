package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.payment.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);

    Optional<Refund> findByGatewayRefundId(String gatewayRefundId);

    long countByPaymentIdAndStatusIn(Long paymentId, List<PaymentStatus> statuses);

    /**
     * DB-level sum of all completed refunds for a payment.
     * Using database aggregation avoids loading all refund rows into memory.
     * Accepts statuses as a parameter for type-safety (JPQL does not allow enum string literals).
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r "
         + "WHERE r.payment.id = :paymentId AND r.status IN :statuses")
    BigDecimal sumCompletedRefundsByPaymentId(
            @Param("paymentId") Long paymentId,
            @Param("statuses") List<PaymentStatus> statuses);

    /**
     * Global sum of all completed refunds (for admin stats).
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status IN :statuses")
    BigDecimal sumAllCompletedRefunds(@Param("statuses") List<PaymentStatus> statuses);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status IN :statuses AND r.payment.bingeId = :bingeId")
    BigDecimal sumAllCompletedRefundsByBingeId(@Param("statuses") List<PaymentStatus> statuses,
                                               @Param("bingeId") Long bingeId);

    /**
     * Batch: sum of completed refunds grouped by payment ID — avoids N+1 in list endpoints.
     * Returns rows of [paymentId, sumAmount].
     */
    @Query("SELECT r.payment.id, COALESCE(SUM(r.amount), 0) FROM Refund r "
         + "WHERE r.payment.id IN :paymentIds AND r.status IN :statuses GROUP BY r.payment.id")
    List<Object[]> sumCompletedRefundsByPaymentIds(
            @Param("paymentIds") List<Long> paymentIds,
            @Param("statuses") List<PaymentStatus> statuses);

    /**
     * Batch: count of completed refunds grouped by payment ID — avoids N+1 in list endpoints.
     * Returns rows of [paymentId, count].
     */
    @Query("SELECT r.payment.id, COUNT(r) FROM Refund r "
         + "WHERE r.payment.id IN :paymentIds AND r.status IN :statuses GROUP BY r.payment.id")
    List<Object[]> countRefundsByPaymentIds(
            @Param("paymentIds") List<Long> paymentIds,
            @Param("statuses") List<PaymentStatus> statuses);

    // ── Refund-lifecycle queries (V10) ─────────────────────────────────────

    /** All refund attempts for a booking (customer-facing timeline + admin audit). */
    @Query("SELECT r FROM Refund r WHERE r.payment.bookingRef = :bookingRef ORDER BY r.createdAt DESC")
    List<Refund> findByBookingRefOrderByCreatedAtDesc(@Param("bookingRef") String bookingRef);

    /** Same, but tenancy-scoped — production code must always pass the bingeId. */
    @Query("SELECT r FROM Refund r WHERE r.payment.bookingRef = :bookingRef "
         + "AND r.payment.bingeId = :bingeId ORDER BY r.createdAt DESC")
    List<Refund> findByBookingRefAndBingeIdOrderByCreatedAtDesc(
            @Param("bookingRef") String bookingRef,
            @Param("bingeId") Long bingeId);

    /** Admin failed-refund queue (paged). */
    org.springframework.data.domain.Page<com.skbingegalaxy.payment.entity.Refund>
        findByRefundStatusAndPayment_BingeIdOrderByCreatedAtDesc(
            com.skbingegalaxy.payment.entity.RefundStatus refundStatus,
            Long bingeId,
            org.springframework.data.domain.Pageable pageable);
}
