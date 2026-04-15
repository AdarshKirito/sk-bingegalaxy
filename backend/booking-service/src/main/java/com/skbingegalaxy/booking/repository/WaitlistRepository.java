package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.WaitlistEntry;
import com.skbingegalaxy.booking.entity.WaitlistEntry.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    /** Next in queue for a specific date at a binge. */
    List<WaitlistEntry> findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
            Long bingeId, LocalDate preferredDate, WaitlistStatus status);

    /** Customer's active waitlist entries. */
    List<WaitlistEntry> findByCustomerIdAndStatusInOrderByCreatedAtDesc(
            Long customerId, List<WaitlistStatus> statuses);

    /** All waitlist entries for a binge (admin view). */
    List<WaitlistEntry> findByBingeIdAndStatusOrderByPreferredDateAscPositionAsc(
            Long bingeId, WaitlistStatus status);

    /** Check if customer already on waitlist for same date/time. */
    boolean existsByBingeIdAndCustomerIdAndPreferredDateAndPreferredStartTimeAndStatusIn(
            Long bingeId, Long customerId, LocalDate date,
            java.time.LocalTime startTime, List<WaitlistStatus> statuses);

    /** Max position for a binge+date (for assigning next position). */
    @Query("SELECT COALESCE(MAX(w.position), 0) FROM WaitlistEntry w " +
           "WHERE w.bingeId = :bingeId AND w.preferredDate = :date")
    int findMaxPosition(@Param("bingeId") Long bingeId, @Param("date") LocalDate date);

    /** Count waiting entries for a date. */
    long countByBingeIdAndPreferredDateAndStatus(Long bingeId, LocalDate date, WaitlistStatus status);

    /** Expired offers that should be moved back or cancelled. */
    List<WaitlistEntry> findByStatusAndOfferExpiresAtBefore(WaitlistStatus status, LocalDateTime now);

    /** Customer's entry by id (for cancel). */
    Optional<WaitlistEntry> findByIdAndCustomerId(Long id, Long customerId);

    /** All entries for a customer at a binge. */
    List<WaitlistEntry> findByBingeIdAndCustomerIdOrderByCreatedAtDesc(Long bingeId, Long customerId);
}
