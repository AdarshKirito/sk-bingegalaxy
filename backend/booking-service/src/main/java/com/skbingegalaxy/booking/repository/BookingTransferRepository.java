package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingTransfer;
import com.skbingegalaxy.booking.entity.BookingTransfer.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingTransferRepository extends JpaRepository<BookingTransfer, Long> {

    Optional<BookingTransfer> findByAcceptToken(String acceptToken);

    List<BookingTransfer> findByBookingRefOrderByCreatedAtDesc(String bookingRef);

    Optional<BookingTransfer> findFirstByBookingRefAndStatus(String bookingRef, Status status);

    /** Anti-abuse: how many transfers has this customer created since {@code since}? */
    long countByFromCustomerIdAndCreatedAtAfter(Long fromCustomerId, LocalDateTime since);

    /** Scheduler: PENDING rows whose expiry has passed. */
    @Query("SELECT t FROM BookingTransfer t "
         + "WHERE t.status = com.skbingegalaxy.booking.entity.BookingTransfer.Status.PENDING "
         + "AND t.expiresAt < :now")
    List<BookingTransfer> findExpiredPending(@Param("now") LocalDateTime now);
}
