package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembershipEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyMembershipEventRepository extends JpaRepository<LoyaltyMembershipEvent, Long> {

    Page<LoyaltyMembershipEvent> findByMembershipIdOrderByCreatedAtDesc(Long membershipId, Pageable pageable);
}
