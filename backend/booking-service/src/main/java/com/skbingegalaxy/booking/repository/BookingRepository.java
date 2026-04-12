package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

       boolean existsByBingeId(Long bingeId);

       boolean existsByEventTypeId(Long eventTypeId);

       Optional<Booking> findByBookingRef(String bookingRef);

       Optional<Booking> findByBookingRefAndBingeId(String bookingRef, Long bingeId);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    List<Booking> findByCustomerIdAndStatus(Long customerId, BookingStatus status);

    List<Booking> findByBookingDateAndStatusNot(LocalDate date, BookingStatus excludeStatus);

    // Admin: by date (paginated, sorted)
    Page<Booking> findByBookingDate(LocalDate date, Pageable pageable);

    // Admin: by status (paginated)
    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    // Admin: by date + status
    Page<Booking> findByBookingDateAndStatus(LocalDate date, BookingStatus status, Pageable pageable);

    // Admin: upcoming = bookingDate >= given date, non-cancelled
    Page<Booking> findByBookingDateGreaterThanEqualAndStatusNot(LocalDate date, BookingStatus excludeStatus, Pageable pageable);

    // Admin: upcoming excluding multiple terminal statuses
    @Query("SELECT b FROM Booking b WHERE b.bookingDate >= :date AND b.status IN ('PENDING', 'CONFIRMED')")
    Page<Booking> findUpcomingBookings(@Param("date") LocalDate date, Pageable pageable);

    // Customer: current bookings (PENDING or CONFIRMED with today or future date)
    @Query("SELECT b FROM Booking b WHERE b.customerId = :cid AND b.bookingDate >= :today AND b.status IN ('PENDING', 'CONFIRMED') ORDER BY b.bookingDate ASC, b.startTime ASC")
    List<Booking> findCustomerCurrentBookings(@Param("cid") Long customerId, @Param("today") LocalDate today);

    // Customer: past bookings (COMPLETED, CANCELLED, NO_SHOW, or past-date non-active)
    @Query("SELECT b FROM Booking b WHERE b.customerId = :cid AND (b.status IN ('COMPLETED', 'CANCELLED', 'NO_SHOW') OR b.bookingDate < :today) ORDER BY b.bookingDate DESC, b.startTime DESC")
    List<Booking> findCustomerPastBookings(@Param("cid") Long customerId, @Param("today") LocalDate today);

    // Admin: date range
    Page<Booking> findByBookingDateBetween(LocalDate from, LocalDate to, Pageable pageable);

    // Today's counts for dashboard
    long countByBookingDate(LocalDate date);

    long countByBookingDateAndStatus(LocalDate date, BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date AND b.checkedIn = :checkedIn AND b.status = 'CHECKED_IN'")
    long countByBookingDateAndCheckedIn(@Param("date") LocalDate date, @Param("checkedIn") boolean checkedIn);

    // Admin search: booking ref, customer name, email, phone, event type name
    @Query("SELECT b FROM Booking b WHERE " +
           "LOWER(b.bookingRef) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "b.customerPhone LIKE CONCAT('%', :q, '%') OR " +
           "LOWER(b.eventType.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<Booking> searchBookings(@Param("q") String query, Pageable pageable);

    Page<Booking> findAll(Pageable pageable);

    long countByStatus(BookingStatus status);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal totalRevenue();

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED') AND b.bookingDate = :date")
    java.math.BigDecimal totalRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED') AND b.bookingDate BETWEEN :from AND :to")
    java.math.BigDecimal totalRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByBookingDateBetween(LocalDate from, LocalDate to);

    // Report: count non-cancelled bookings for date
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date AND b.status <> 'CANCELLED'")
    long countNonCancelledByDate(@Param("date") LocalDate date);

    // Report: count non-cancelled bookings for date range
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED'")
    long countNonCancelledByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Estimated revenue: sum of totalAmount for all non-cancelled bookings (regardless of payment status)
    @Query("SELECT COALESCE(SUM(COALESCE(b.totalAmount, 0)), 0) FROM Booking b WHERE b.bookingDate = :date AND b.status <> 'CANCELLED'")
    java.math.BigDecimal estimatedRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.totalAmount, 0)), 0) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED'")
    java.math.BigDecimal estimatedRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Actual revenue: sum of collected amounts for non-cancelled bookings with any successful payment
    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate = :date AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal actualRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal actualRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // House accounts: pending payment bookings
    Page<Booking> findByPaymentStatus(PaymentStatus paymentStatus, Pageable pageable);

    // Count bookings by customer
    long countByCustomerId(Long customerId);

    // Find active (non-cancelled) bookings for a given date to prevent double-booking
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bookingDate = :date AND b.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Booking> findActiveBookingsByDate(@Param("date") LocalDate date);

       @Query("SELECT b FROM Booking b WHERE b.bookingDate = :date AND b.status NOT IN ('CANCELLED', 'NO_SHOW')")
       List<Booking> findActiveBookingsForReadByDate(@Param("date") LocalDate date);

    // ═══════════════════════════════════════════════════════════
    //  BINGE-SCOPED QUERIES
    // ═══════════════════════════════════════════════════════════

    Page<Booking> findByBingeId(Long bingeId, Pageable pageable);
    Page<Booking> findByBingeIdAndBookingDate(Long bingeId, LocalDate date, Pageable pageable);
    Page<Booking> findByBingeIdAndStatus(Long bingeId, BookingStatus status, Pageable pageable);
    Page<Booking> findByBingeIdAndBookingDateAndStatus(Long bingeId, LocalDate date, BookingStatus status, Pageable pageable);
    Page<Booking> findByBingeIdAndBookingDateBetween(Long bingeId, LocalDate from, LocalDate to, Pageable pageable);
    List<Booking> findByBingeIdAndCustomerIdAndStatus(Long bingeId, Long customerId, BookingStatus status);

    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate >= :date AND b.status IN ('PENDING', 'CONFIRMED')")
    Page<Booking> findUpcomingBookingsByBinge(@Param("bid") Long bingeId, @Param("date") LocalDate date, Pageable pageable);

    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND (" +
           "LOWER(b.bookingRef) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "b.customerPhone LIKE CONCAT('%', :q, '%') OR " +
           "LOWER(b.eventType.name) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Booking> searchBookingsByBinge(@Param("bid") Long bingeId, @Param("q") String query, Pageable pageable);

    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND (" +
           "LOWER(b.bookingRef) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "b.customerPhone LIKE CONCAT('%', :q, '%') OR " +
           "LOWER(b.eventType.name) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Booking> searchBookingsByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date, @Param("q") String query, Pageable pageable);

    @Query("SELECT b FROM Booking b WHERE b.bookingDate = :date AND (" +
           "LOWER(b.bookingRef) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "b.customerPhone LIKE CONCAT('%', :q, '%') OR " +
           "LOWER(b.eventType.name) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Booking> searchBookingsByDate(@Param("date") LocalDate date, @Param("q") String query, Pageable pageable);

    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.customerId = :cid AND b.bookingDate >= :today AND b.status IN ('PENDING', 'CONFIRMED') ORDER BY b.bookingDate ASC, b.startTime ASC")
    List<Booking> findCustomerCurrentBookingsByBinge(@Param("bid") Long bingeId, @Param("cid") Long customerId, @Param("today") LocalDate today);

    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.customerId = :cid AND (b.status IN ('COMPLETED', 'CANCELLED', 'NO_SHOW') OR b.bookingDate < :today) ORDER BY b.bookingDate DESC, b.startTime DESC")
    List<Booking> findCustomerPastBookingsByBinge(@Param("bid") Long bingeId, @Param("cid") Long customerId, @Param("today") LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Booking> findActiveBookingsByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date);

       @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.status NOT IN ('CANCELLED', 'NO_SHOW')")
       List<Booking> findActiveBookingsForReadByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date);

    // Dashboard counts (binge-scoped)
    long countByBingeIdAndBookingDate(Long bingeId, LocalDate date);
    long countByBingeIdAndBookingDateAndStatus(Long bingeId, LocalDate date, BookingStatus status);
    long countByBingeIdAndStatus(Long bingeId, BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.checkedIn = :ci AND b.status = 'CHECKED_IN'")
    long countByBingeAndDateAndCheckedIn(@Param("bid") Long bingeId, @Param("date") LocalDate date, @Param("ci") boolean checkedIn);

    // Revenue (binge-scoped)
    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal actualRevenueByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.totalAmount, 0)), 0) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.status <> 'CANCELLED'")
    java.math.BigDecimal estimatedRevenueByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.status <> 'CANCELLED'")
    long countNonCancelledByBingeAndDate(@Param("bid") Long bingeId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED'")
    long countNonCancelledByBingeAndDateRange(@Param("bid") Long bingeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_PAID', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal actualRevenueByBingeAndDateRange(@Param("bid") Long bingeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(COALESCE(b.totalAmount, 0)), 0) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED'")
    java.math.BigDecimal estimatedRevenueByBingeAndDateRange(@Param("bid") Long bingeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<Booking> findByBingeIdAndCustomerIdOrderByCreatedAtDesc(Long bingeId, Long customerId);
    long countByBingeIdAndCustomerId(Long bingeId, Long customerId);
    Page<Booking> findByBingeIdAndPaymentStatus(Long bingeId, PaymentStatus paymentStatus, Pageable pageable);

    // Saga: find PENDING bookings older than a cutoff (for timeout cancellation)
    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' AND b.paymentStatus = 'PENDING' AND b.createdAt < :cutoff")
    List<Booking> findStalePendingBookings(@Param("cutoff") LocalDateTime cutoff);

    // Anti-abuse: count current PENDING bookings for a customer
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'PENDING' AND b.paymentStatus = 'PENDING'")
    long countPendingByCustomerId(@Param("cid") Long customerId);

    // Anti-abuse: count bookings auto-cancelled due to payment timeout in the recent window
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'CANCELLED' AND b.paymentStatus = 'PENDING' AND b.updatedAt > :since")
    long countRecentTimeoutCancellations(@Param("cid") Long customerId, @Param("since") LocalDateTime since);

    /**
     * Acquires a transaction-scoped advisory lock keyed on (bingeId, date).
     * Serialises all booking-creation attempts for the same binge + date so that
     * the subsequent {@code hasTimeConflict} check and INSERT are atomic —
     * eliminating the "first booking of the day" race where no rows exist to
     * pessimistic-lock.  The lock is automatically released at transaction commit.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    void acquireSlotLock(@Param("lockKey") long lockKey);

    // Atomic collected-amount updates (avoids read-modify-write race conditions)
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.collectedAmount = COALESCE(b.collectedAmount, 0) + :amount WHERE b.bookingRef = :ref")
    int addToCollectedAmount(@Param("ref") String bookingRef, @Param("amount") java.math.BigDecimal amount);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Booking b SET b.collectedAmount = CASE WHEN COALESCE(b.collectedAmount, 0) - :amount < 0 THEN 0 ELSE COALESCE(b.collectedAmount, 0) - :amount END WHERE b.bookingRef = :ref")
    int subtractFromCollectedAmount(@Param("ref") String bookingRef, @Param("amount") java.math.BigDecimal amount);
}
