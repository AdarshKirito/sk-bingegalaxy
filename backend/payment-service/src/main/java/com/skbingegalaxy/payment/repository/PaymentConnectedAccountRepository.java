package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.PaymentConnectedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentConnectedAccountRepository extends JpaRepository<PaymentConnectedAccount, Long> {

    /** The venue's gateway account, if it has been onboarded. */
    Optional<PaymentConnectedAccount> findByBingeId(Long bingeId);

    /** Reverse lookup for webhooks, which arrive keyed by Stripe account id. */
    Optional<PaymentConnectedAccount> findByAccountId(String accountId);

    boolean existsByBingeId(Long bingeId);
}
