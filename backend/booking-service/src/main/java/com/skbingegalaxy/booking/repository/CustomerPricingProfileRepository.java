package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CustomerPricingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerPricingProfileRepository extends JpaRepository<CustomerPricingProfile, Long> {
    Optional<CustomerPricingProfile> findByCustomerId(Long customerId);
    List<CustomerPricingProfile> findByRateCodeId(Long rateCodeId);
    boolean existsByCustomerId(Long customerId);
}
