package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingReadModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingReadModelRepository extends JpaRepository<BookingReadModel, Long> {

    Optional<BookingReadModel> findByBookingRef(String bookingRef);

    void deleteByBookingRef(String bookingRef);
}
