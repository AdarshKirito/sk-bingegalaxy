package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingePerkOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyBingePerkOverrideRepository extends JpaRepository<LoyaltyBingePerkOverride, Long> {

    Optional<LoyaltyBingePerkOverride> findByBindingIdAndPerkId(Long bindingId, Long perkId);

    List<LoyaltyBingePerkOverride> findByBindingId(Long bindingId);
}
