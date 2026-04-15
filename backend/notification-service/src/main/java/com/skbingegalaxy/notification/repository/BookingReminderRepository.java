package com.skbingegalaxy.notification.repository;

import com.skbingegalaxy.notification.model.BookingReminder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BookingReminderRepository extends MongoRepository<BookingReminder, String> {

    List<BookingReminder> findByFiredFalseAndCancelledFalseAndFireAtBefore(LocalDateTime cutoff);

    List<BookingReminder> findByBookingRef(String bookingRef);
}
