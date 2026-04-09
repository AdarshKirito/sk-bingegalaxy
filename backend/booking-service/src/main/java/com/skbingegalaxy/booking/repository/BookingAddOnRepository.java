package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BookingAddOn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingAddOnRepository extends JpaRepository<BookingAddOn, Long> {
    boolean existsByAddOnId(Long addOnId);
}