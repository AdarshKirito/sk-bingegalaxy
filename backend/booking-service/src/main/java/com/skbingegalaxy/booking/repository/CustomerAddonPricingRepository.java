package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CustomerAddonPricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAddonPricingRepository extends JpaRepository<CustomerAddonPricing, Long> {
    boolean existsByAddOnId(Long addOnId);
}