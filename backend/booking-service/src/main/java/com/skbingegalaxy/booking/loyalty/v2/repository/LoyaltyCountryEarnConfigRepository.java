package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyCountryEarnConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyCountryEarnConfigRepository
        extends JpaRepository<LoyaltyCountryEarnConfig, String> {

    Optional<LoyaltyCountryEarnConfig> findByCountryIso2AndActiveTrue(String countryIso2);
}
