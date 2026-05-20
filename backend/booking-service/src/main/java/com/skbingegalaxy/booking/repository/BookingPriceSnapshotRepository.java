package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingPriceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingPriceSnapshotRepository extends JpaRepository<BookingPriceSnapshot, Long> {
    List<BookingPriceSnapshot> findByBookingRefOrderByCreatedAtDesc(String bookingRef);
}
