package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingEventLog;
import com.skbingegalaxy.booking.entity.BookingEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingEventLogRepository extends JpaRepository<BookingEventLog, Long> {

    List<BookingEventLog> findByBookingRefOrderByCreatedAtAsc(String bookingRef);

    Page<BookingEventLog> findByBookingRefOrderByCreatedAtAsc(String bookingRef, Pageable pageable);

    List<BookingEventLog> findByBookingRefAndEventTypeOrderByCreatedAtDesc(String bookingRef, BookingEventType eventType);
}
