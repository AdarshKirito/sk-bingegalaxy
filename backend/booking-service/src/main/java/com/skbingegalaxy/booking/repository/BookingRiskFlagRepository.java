package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingRiskFlag;
import com.skbingegalaxy.booking.entity.BookingRiskFlag.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingRiskFlagRepository extends JpaRepository<BookingRiskFlag, Long> {

    List<BookingRiskFlag> findByBookingRefOrderByCreatedAtDesc(String bookingRef);

    List<BookingRiskFlag> findByCustomerIdAndAcknowledgedFalseOrderByCreatedAtDesc(Long customerId);

    Page<BookingRiskFlag> findByBingeIdAndAcknowledgedFalseOrderBySeverityDescCreatedAtDesc(
        Long bingeId, Pageable pageable);

    Page<BookingRiskFlag> findByBingeIdOrderByCreatedAtDesc(Long bingeId, Pageable pageable);

    /**
     * Whether an identical (bookingRef, ruleCode) flag already exists. Used by
     * the risk evaluator to keep its rules idempotent — re-running detection
     * shouldn't pile up duplicate rows for the same finding on the same
     * booking. Acknowledged flags do NOT count: if an admin acknowledged an
     * earlier instance and the rule fires again later, that's a fresh signal.
     */
    @Query("SELECT COUNT(f) > 0 FROM BookingRiskFlag f WHERE f.bookingRef = :ref AND f.ruleCode = :rule AND f.acknowledged = false")
    boolean existsOpenForBookingAndRule(@Param("ref") String bookingRef,
                                        @Param("rule") BookingRiskFlag.RuleCode rule);

    long countByBingeIdAndAcknowledgedFalseAndSeverity(Long bingeId, Severity severity);

    @Query("SELECT COUNT(f) FROM BookingRiskFlag f WHERE f.customerId = :cid AND f.createdAt > :since")
    long countByCustomerIdSince(@Param("cid") Long customerId, @Param("since") LocalDateTime since);
}
