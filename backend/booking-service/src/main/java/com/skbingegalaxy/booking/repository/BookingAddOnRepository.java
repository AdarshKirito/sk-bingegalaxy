package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingAddOn;
import com.skbingegalaxy.common.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;

public interface BookingAddOnRepository extends JpaRepository<BookingAddOn, Long> {
    boolean existsByAddOnId(Long addOnId);

    /**
     * Sum of quantities of an add-on already booked for a given date,
     * counting only bookings whose status is in {@code activeStatuses}.
     * Used to enforce {@code AddOn.stockPerDay} caps.
     */
    @Query("""
        SELECT COALESCE(SUM(ba.quantity), 0)
        FROM BookingAddOn ba
        WHERE ba.addOn.id = :addOnId
          AND ba.booking.bookingDate = :date
          AND ba.booking.status IN :statuses
          AND (:excludeBookingId IS NULL OR ba.booking.id <> :excludeBookingId)
        """)
    long sumQuantityForAddOnOnDate(
        @Param("addOnId") Long addOnId,
        @Param("date") LocalDate date,
        @Param("statuses") Collection<BookingStatus> statuses,
        @Param("excludeBookingId") Long excludeBookingId);
}