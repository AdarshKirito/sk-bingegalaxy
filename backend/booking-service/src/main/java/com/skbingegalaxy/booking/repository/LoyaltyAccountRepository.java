package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByCustomerIdAndBingeId(Long customerId, Long bingeId);
}
