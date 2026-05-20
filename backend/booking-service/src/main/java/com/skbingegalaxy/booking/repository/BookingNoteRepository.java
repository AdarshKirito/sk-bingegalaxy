package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingNoteRepository extends JpaRepository<BookingNote, Long> {

    List<BookingNote> findByBookingRefAndDeletedFalseOrderByPinnedDescCreatedAtDesc(String bookingRef);
}
