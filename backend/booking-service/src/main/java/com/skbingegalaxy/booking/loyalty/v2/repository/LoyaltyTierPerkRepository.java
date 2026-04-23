package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyTierPerk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTierPerkRepository extends JpaRepository<LoyaltyTierPerk, Long> {

    List<LoyaltyTierPerk> findByTierDefinitionIdOrderBySortOrderAsc(Long tierDefinitionId);

    List<LoyaltyTierPerk> findByTierDefinitionIdIn(List<Long> tierDefinitionIds);
}
