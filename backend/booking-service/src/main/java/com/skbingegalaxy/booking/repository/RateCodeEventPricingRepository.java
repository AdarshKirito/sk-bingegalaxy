package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.RateCodeEventPricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateCodeEventPricingRepository extends JpaRepository<RateCodeEventPricing, Long> {
    boolean existsByEventTypeId(Long eventTypeId);
}