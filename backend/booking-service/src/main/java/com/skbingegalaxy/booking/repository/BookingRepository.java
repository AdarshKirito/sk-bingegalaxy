package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingRef(String bookingRef);

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

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date AND b.checkedIn = :checkedIn")
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

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus = 'SUCCESS'")
    java.math.BigDecimal totalRevenue();

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus = 'SUCCESS' AND b.bookingDate = :date")
    java.math.BigDecimal totalRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.paymentStatus = 'SUCCESS' AND b.bookingDate BETWEEN :from AND :to")
    java.math.BigDecimal totalRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByBookingDateBetween(LocalDate from, LocalDate to);

    // Report: count non-cancelled bookings for date
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date AND b.status <> 'CANCELLED'")
    long countNonCancelledByDate(@Param("date") LocalDate date);

    // Report: count non-cancelled bookings for date range
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED'")
    long countNonCancelledByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Estimated revenue: sum of what has actually been collected (collectedAmount) for active bookings
    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate = :date AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal estimatedRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status <> 'CANCELLED' AND b.paymentStatus IN ('SUCCESS', 'PARTIALLY_REFUNDED')")
    java.math.BigDecimal estimatedRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Actual revenue: only COMPLETED + payment SUCCESS (money actually collected)
    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate = :date AND b.status = 'COMPLETED' AND b.paymentStatus = 'SUCCESS'")
    java.math.BigDecimal actualRevenueByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(COALESCE(b.collectedAmount, 0)), 0) FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to AND b.status = 'COMPLETED' AND b.paymentStatus = 'SUCCESS'")
    java.math.BigDecimal actualRevenueByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // House accounts: pending payment bookings
    Page<Booking> findByPaymentStatus(PaymentStatus paymentStatus, Pageable pageable);

    // Count bookings by customer
    long countByCustomerId(Long customerId);

    // Find active (non-cancelled) bookings for a given date to prevent double-booking
    @Query("SELECT b FROM Booking b WHERE b.bookingDate = :date AND b.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Booking> findActiveBookingsByDate(@Param("date") LocalDate date);
}
