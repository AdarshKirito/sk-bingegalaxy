package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.PaymentDispute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentDisputeRepository extends JpaRepository<PaymentDispute, Long> {

    Optional<PaymentDispute> findByGatewayDisputeId(String gatewayDisputeId);

    boolean existsByGatewayDisputeId(String gatewayDisputeId);

    List<PaymentDispute> findByPayment_IdOrderByCreatedAtDesc(Long paymentId);

    // JOIN FETCH payment to avoid N+1 when toDto accesses payment.transactionId.
    // countQuery omits the JOIN FETCH (unsupported with COUNT) and uses a lean count.
    // Sort: disputes with nearest deadline first; no-deadline entries sort to the end.
    @Query(value = "SELECT d FROM PaymentDispute d JOIN FETCH d.payment WHERE d.bingeId = :bingeId AND d.status NOT IN ('WON','LOST','ACCEPTED') ORDER BY CASE WHEN d.respondBy IS NULL THEN 1 ELSE 0 END, d.respondBy ASC",
           countQuery = "SELECT COUNT(d) FROM PaymentDispute d WHERE d.bingeId = :bingeId AND d.status NOT IN ('WON','LOST','ACCEPTED')")
    Page<PaymentDispute> findOpenByBingeId(@Param("bingeId") Long bingeId, Pageable pageable);

    @Query(value = "SELECT d FROM PaymentDispute d JOIN FETCH d.payment WHERE d.bingeId = :bingeId ORDER BY d.createdAt DESC",
           countQuery = "SELECT COUNT(d) FROM PaymentDispute d WHERE d.bingeId = :bingeId")
    Page<PaymentDispute> findAllByBingeId(@Param("bingeId") Long bingeId, Pageable pageable);

    long countByBingeIdAndStatusNotIn(Long bingeId, List<String> terminalStatuses);
}
