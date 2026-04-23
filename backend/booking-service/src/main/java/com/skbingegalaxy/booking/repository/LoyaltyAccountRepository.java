package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.LoyaltyAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByCustomerId(Long customerId);

    /**
     * Bulk lookup — used when we need tier info for many customers at
     * once (e.g. the weighted binge-rating calculation).  Avoids an
     * N+1 query per review and beats {@code findAll()} which would
     * stream the entire table.
     */
    java.util.List<LoyaltyAccount> findByCustomerIdIn(java.util.Collection<Long> customerIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.customerId = :customerId")
    Optional<LoyaltyAccount> findByCustomerIdForUpdate(Long customerId);

    /**
     * Pessimistic-lock variant for {@link #findById} — required by the
     * scheduled points-expiry job so it can't race with a concurrent
     * redeem operation (which holds a write lock via
     * {@link #findByCustomerIdForUpdate}) and clobber the wallet balance
     * with a stale read-modify-write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.id = :id")
    Optional<LoyaltyAccount> findByIdForUpdate(Long id);
}
