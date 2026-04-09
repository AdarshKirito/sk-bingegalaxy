package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CustomerEventPricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerEventPricingRepository extends JpaRepository<CustomerEventPricing, Long> {
    boolean existsByEventTypeId(Long eventTypeId);
}