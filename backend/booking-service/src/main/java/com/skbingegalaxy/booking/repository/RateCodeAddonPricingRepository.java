package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.RateCodeAddonPricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateCodeAddonPricingRepository extends JpaRepository<RateCodeAddonPricing, Long> {
    boolean existsByAddOnId(Long addOnId);
}