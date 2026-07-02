package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

       boolean existsByBingeId(Long bingeId);

       boolean existsByEventTypeId(Long eventTypeId);

       Optional<Booking> findByBookingRef(String bookingRef);

       Optional<Booking> findByBookingRefAndBingeId(String bookingRef, Long bingeId);

    @EntityGraph(attributePaths = {"eventType"})
    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"eventType"})
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
    @EntityGraph(attributePaths = {"eventType"})
    @Query("SELECT b FROM Booking b WHERE b.customerId = :cid AND b.bookingDate >= :today AND b.status IN ('PENDING', 'CONFIRMED') ORDER BY b.bookingDate ASC, b.startTime ASC")
    List<Booking> findCustomerCurrentBookings(@Param("cid") Long customerId, @Param("today") LocalDate today);

    // Customer: past bookings (COMPLETED, CANCELLED, NO_SHOW, or past-date non-active)
    @EntityGraph(attributePaths = {"eventType"})
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

    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByBingeId(Long bingeId, Pageable pageable);
    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByBingeIdAndBookingDate(Long bingeId, LocalDate date, Pageable pageable);
    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByBingeIdAndStatus(Long bingeId, BookingStatus status, Pageable pageable);
    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByBingeIdAndBookingDateAndStatus(Long bingeId, LocalDate date, BookingStatus status, Pageable pageable);
    @EntityGraph(attributePaths = {"eventType"})
    Page<Booking> findByBingeIdAndBookingDateBetween(Long bingeId, LocalDate from, LocalDate to, Pageable pageable);
    List<Booking> findByBingeIdAndBookingDateBetweenOrderByBookingDateAscStartTimeAsc(Long bingeId, LocalDate from, LocalDate to);
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

    @EntityGraph(attributePaths = {"eventType"})
    @Query("SELECT b FROM Booking b WHERE b.bingeId = :bid AND b.customerId = :cid AND b.bookingDate >= :today AND b.status IN ('PENDING', 'CONFIRMED') ORDER BY b.bookingDate ASC, b.startTime ASC")
    List<Booking> findCustomerCurrentBookingsByBinge(@Param("bid") Long bingeId, @Param("cid") Long customerId, @Param("today") LocalDate today);

    @EntityGraph(attributePaths = {"eventType"})
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

    /**
     * Candidate set for the no-show sweep: still-active (PENDING/CONFIRMED),
     * not-yet-checked-in bookings whose {@code bookingDate} falls in
     * {@code [from, to]}. The date range is only a coarse, index-friendly bound
     * that brackets <em>every</em> venue timezone (±14h from UTC). The scheduler
     * then resolves each booking's venue-local clock and only marks NO_SHOW once
     * the reservation has passed its <b>midpoint</b> (start + duration/2) — never
     * a fixed grace window — so the cutoff scales with the reservation length.
     *
     * <p>bookingDate/startTime are venue-local, so the precise time comparison
     * MUST happen in the scheduler against {@code VenueClockService}, not here.
     */
    @Query("SELECT b FROM Booking b WHERE b.bookingDate BETWEEN :from AND :to "
         + "AND b.status IN ('PENDING','CONFIRMED') AND b.checkedIn = false")
    List<Booking> findNoShowSweepCandidates(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid AND b.bookingDate = :date AND b.checkedIn = :ci AND b.status = 'CHECKED_IN'")
    long countByBingeAndDateAndCheckedIn(@Param("bid") Long bingeId, @Param("date") LocalDate date, @Param("ci") boolean checkedIn);

    /**
     * Physical-occupancy count for a specific room: bookings currently
     * CHECKED_IN (i.e. guests physically present, not yet checked out) in
     * {@code roomId} on {@code date}, excluding {@code excludeId}. Used to stop a
     * second party being checked into a room that is already at capacity.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE (:bid IS NULL OR b.bingeId = :bid) "
         + "AND b.bookingDate = :date AND b.status = 'CHECKED_IN' "
         + "AND b.venueRoomId = :roomId AND b.id <> :excludeId")
    long countActiveCheckInsInRoom(@Param("bid") Long bingeId, @Param("date") LocalDate date,
                                   @Param("roomId") Long roomId, @Param("excludeId") Long excludeId);

    /**
     * Physical-occupancy count for a room-less venue: bookings currently
     * CHECKED_IN with no room assigned in this binge on {@code date}, excluding
     * {@code excludeId}. A room-less venue is a single physical space, so any
     * live check-in occupies it.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE (:bid IS NULL OR b.bingeId = :bid) "
         + "AND b.bookingDate = :date AND b.status = 'CHECKED_IN' "
         + "AND b.venueRoomId IS NULL AND b.id <> :excludeId")
    long countActiveCheckInsInVenue(@Param("bid") Long bingeId, @Param("date") LocalDate date,
                                    @Param("excludeId") Long excludeId);

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

    @EntityGraph(attributePaths = {"eventType"})
    List<Booking> findByBingeIdAndCustomerIdOrderByCreatedAtDesc(Long bingeId, Long customerId);
    long countByBingeIdAndCustomerId(Long bingeId, Long customerId);
    Page<Booking> findByBingeIdAndPaymentStatus(Long bingeId, PaymentStatus paymentStatus, Pageable pageable);

    // Saga: find PENDING bookings older than a cutoff (for timeout cancellation)
    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' AND b.paymentStatus = 'PENDING' AND b.createdAt < :cutoff")
    List<Booking> findStalePendingBookings(@Param("cutoff") LocalDateTime cutoff);

    // Recovery queue: bookings paid by the customer but never marked CONFIRMED
    // by the saga. Indicates the BOOKING_CONFIRMED side of the
    // payment.success → confirm transition is stuck.
    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' " +
           "AND b.paymentStatus = 'SUCCESS' AND b.updatedAt < :cutoff " +
           "ORDER BY b.updatedAt ASC")
    Page<Booking> findPaidButNotConfirmed(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    // Recovery queue: NO_SHOW bookings within a date range — admin reviews
    // these to follow up with customers and reconcile partial refunds.
    @Query("SELECT b FROM Booking b WHERE b.status = 'NO_SHOW' " +
           "AND b.bookingDate BETWEEN :from AND :to ORDER BY b.bookingDate DESC")
    Page<Booking> findNoShowBookings(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     Pageable pageable);

    // Recovery queue: stuck pending — paged variant of findStalePendingBookings.
    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' " +
           "AND b.paymentStatus = 'PENDING' AND b.createdAt < :cutoff " +
           "ORDER BY b.createdAt ASC")
    Page<Booking> findStuckPending(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);


    // ── Funnel analytics (binge-scoped via service layer) ────
    // All filter on createdAt (when the booking was started) so a booking that
    // ends up CANCELLED still counts toward the "Started" stage. Aggregates are
    // counts only — no PII flows through these queries.

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid " +
           "AND b.createdAt >= :from AND b.createdAt < :to")
    long countCreatedInRange(@Param("bid") Long bingeId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid " +
           "AND b.createdAt >= :from AND b.createdAt < :to AND b.status = :status")
    long countCreatedInRangeByStatus(@Param("bid") Long bingeId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("status") BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid " +
           "AND b.createdAt >= :from AND b.createdAt < :to AND b.paymentStatus = :paymentStatus")
    long countCreatedInRangeByPaymentStatus(@Param("bid") Long bingeId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to,
                                            @Param("paymentStatus") PaymentStatus paymentStatus);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bingeId = :bid " +
           "AND b.createdAt >= :from AND b.createdAt < :to " +
           "AND b.status = 'CANCELLED' AND b.cancellationActor = :actor")
    long countCancelledByActor(@Param("bid") Long bingeId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("actor") String cancellationActor);


    // Anti-abuse: count current PENDING bookings for a customer (across all binges, kept for legacy callers)
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'PENDING' AND b.paymentStatus = 'PENDING'")
    long countPendingByCustomerId(@Param("cid") Long customerId);

    // Anti-abuse: count current PENDING bookings for a customer scoped to one binge.
    // Per-binge scoping prevents cross-venue blocking (a customer with pending payments
    // at venue A should still be able to book at venue B).
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.bingeId = :bid AND b.status = 'PENDING' AND b.paymentStatus = 'PENDING'")
    long countPendingByCustomerIdAndBingeId(@Param("cid") Long customerId, @Param("bid") Long bingeId);

    /**
     * Content-based duplicate check used to refuse a second PENDING booking for
     * the same customer + event + slot. Sits alongside Idempotency-Key + the
     * gateway rate limiter as defence in depth.
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN TRUE ELSE FALSE END FROM Booking b " +
           "WHERE b.customerId = :cid AND b.eventType.id = :eventTypeId " +
           "AND b.bookingDate = :date AND b.startTime = :startTime " +
           "AND b.status = 'PENDING'")
    boolean existsPendingDuplicate(@Param("cid") Long customerId,
                                   @Param("eventTypeId") Long eventTypeId,
                                   @Param("date") LocalDate date,
                                   @Param("startTime") LocalTime startTime);

    // Anti-abuse: count bookings auto-cancelled due to payment timeout in the recent window
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'CANCELLED' AND b.paymentStatus = 'PENDING' AND b.updatedAt > :since")
    long countRecentTimeoutCancellations(@Param("cid") Long customerId, @Param("since") LocalDateTime since);

    // Freeze-policy: payment-timeout count scoped to a specific binge.
    // Filters on cancellationActor='SYSTEM' so customer cancels don't count here.
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.bingeId = :bid AND b.status = 'CANCELLED' AND b.paymentStatus = 'PENDING' AND b.cancellationActor = 'SYSTEM' AND b.updatedAt > :since")
    long countRecentTimeoutCancellationsByBinge(@Param("cid") Long customerId, @Param("bid") Long bingeId, @Param("since") LocalDateTime since);

    // Freeze-policy: customer-initiated cancellations of pending bookings within window.
    // Filters on cancellationActor='CUSTOMER' so payment-timeout cancels don't count here.
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.bingeId = :bid AND b.status = 'CANCELLED' AND b.paymentStatus = 'PENDING' AND b.cancellationActor = 'CUSTOMER' AND b.updatedAt > :since")
    long countCustomerPendingCancelsSince(@Param("cid") Long customerId, @Param("bid") Long bingeId, @Param("since") LocalDateTime since);

    // Freeze-policy: NO_SHOW bookings scoped to a specific binge within the window.
    // The daily audit scheduler flips status to NO_SHOW and bumps updatedAt, so
    // we filter on (status=NO_SHOW, updatedAt>since). Booking date is the more
    // semantically correct timestamp but updatedAt aligns with the other counters
    // and gives operators a consistent rolling-window meaning across all 3 triggers.
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.bingeId = :bid AND b.status = 'NO_SHOW' AND b.updatedAt > :since")
    long countNoShowsByBingeSince(@Param("cid") Long customerId, @Param("bid") Long bingeId, @Param("since") LocalDateTime since);

    // ── Risk evaluator helpers (fraud / abuse — Item 23) ─────────────────
    /**
     * Count of distinct {@code customerId} values that have ever booked using
     * the given phone number. The risk evaluator flags a booking when the
     * count exceeds a threshold (default 2) — phone numbers SHOULD be
     * one-to-one with customer accounts; a higher count is a strong
     * shared-account or duplicate-account signal.
     */
    @Query("SELECT COUNT(DISTINCT b.customerId) FROM Booking b WHERE b.customerPhone = :phone")
    long countDistinctCustomersByPhone(@Param("phone") String phone);

    @Query("SELECT COUNT(DISTINCT b.customerId) FROM Booking b WHERE LOWER(b.customerEmail) = LOWER(:email)")
    long countDistinctCustomersByEmail(@Param("email") String email);

    /**
     * Burst detection: how many bookings the customer created in the last
     * window. Used by the risk evaluator's RAPID_REBOOKING_BURST rule.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.createdAt > :since")
    long countByCustomerIdCreatedSince(@Param("cid") Long customerId, @Param("since") LocalDateTime since);

    /**
     * Cross-binge cancellation count for risk scoring. Counts ALL customer-
     * initiated cancels, not just within a single binge — abusers rotate
     * binges to evade per-binge freezes (see audit, item 23).
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'CANCELLED' AND b.cancellationActor = 'CUSTOMER' AND b.updatedAt > :since")
    long countCustomerCancelsAcrossBingesSince(@Param("cid") Long customerId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.customerId = :cid AND b.status = 'NO_SHOW' AND b.updatedAt > :since")
    long countNoShowsAcrossBingesSince(@Param("cid") Long customerId, @Param("since") LocalDateTime since);

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
    // Recurring group queries
    List<Booking> findByRecurringGroupId(String recurringGroupId);

    List<Booking> findByRecurringGroupIdAndBingeId(String recurringGroupId, Long bingeId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.collectedAmount = COALESCE(b.collectedAmount, 0) + :amount WHERE b.bookingRef = :ref")
    int addToCollectedAmount(@Param("ref") String bookingRef, @Param("amount") java.math.BigDecimal amount);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Booking b SET b.collectedAmount = CASE WHEN COALESCE(b.collectedAmount, 0) - :amount < 0 THEN 0 ELSE COALESCE(b.collectedAmount, 0) - :amount END WHERE b.bookingRef = :ref")
    int subtractFromCollectedAmount(@Param("ref") String bookingRef, @Param("amount") java.math.BigDecimal amount);
}
