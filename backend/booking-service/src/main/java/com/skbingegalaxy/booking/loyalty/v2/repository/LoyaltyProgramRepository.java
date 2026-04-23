package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, Long> {

    Optional<LoyaltyProgram> findByCode(String code);

    Optional<LoyaltyProgram> findByCodeAndActiveTrue(String code);
}
