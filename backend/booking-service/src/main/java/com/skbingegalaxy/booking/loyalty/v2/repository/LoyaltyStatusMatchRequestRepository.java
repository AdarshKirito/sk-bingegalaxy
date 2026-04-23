package com.skbingegalaxy.booking.loyalty.v2.repository;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyStatusMatchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LoyaltyStatusMatchRequestRepository extends JpaRepository<LoyaltyStatusMatchRequest, Long> {

    Page<LoyaltyStatusMatchRequest> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<LoyaltyStatusMatchRequest> findByMembershipIdOrderByCreatedAtDesc(Long membershipId);

    List<LoyaltyStatusMatchRequest> findByMembershipIdAndStatusIn(Long membershipId, List<String> statuses);

    List<LoyaltyStatusMatchRequest> findByStatusAndChallengeExpiresAtBefore(String status, LocalDateTime cutoff);
}
