package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CustomerBingeFreeze;
import com.skbingegalaxy.booking.entity.CustomerBingeFreeze.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerBingeFreezeRepository extends JpaRepository<CustomerBingeFreeze, Long> {

    /**
     * Returns the most recent ACTIVE freeze for the (customer, binge) pair, if any,
     * regardless of whether it has expired in wall-clock terms (caller decides).
     */
    Optional<CustomerBingeFreeze> findFirstByCustomerIdAndBingeIdAndStatusOrderByFreezeUntilDesc(
        Long customerId, Long bingeId, Status status);

    /** All ACTIVE freezes for a given binge — used for the admin overview list. */
    List<CustomerBingeFreeze> findByBingeIdAndStatusOrderByFreezeUntilDesc(Long bingeId, Status status);

    /** All freezes (any status) for the customer at the binge — history view. */
    List<CustomerBingeFreeze> findByCustomerIdAndBingeIdOrderByCreatedAtDesc(Long customerId, Long bingeId);

    /** All ACTIVE freezes for the customer (across binges) — customer self-view. */
    List<CustomerBingeFreeze> findByCustomerIdAndStatusOrderByFreezeUntilDesc(Long customerId, Status status);

    /**
     * Background sweeper helper — find ACTIVE freezes that have expired so they
     * can be flipped to EXPIRED on read.
     */
    @Query("SELECT f FROM CustomerBingeFreeze f WHERE f.status = 'ACTIVE' AND f.freezeUntil <= :now")
    List<CustomerBingeFreeze> findActiveButExpired(@Param("now") LocalDateTime now);
}
