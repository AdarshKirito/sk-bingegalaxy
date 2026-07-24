package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BingeChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BingeChangeRequestRepository extends JpaRepository<BingeChangeRequest, Long> {

    /** Duplicate-guard: at most one PENDING request per binge + type. */
    Optional<BingeChangeRequest> findFirstByBingeIdAndRequestTypeAndStatus(
        Long bingeId, BingeChangeRequest.Type type, BingeChangeRequest.Status status);

    List<BingeChangeRequest> findByStatusOrderByCreatedAtDesc(BingeChangeRequest.Status status);

    List<BingeChangeRequest> findAllByOrderByCreatedAtDesc();

    List<BingeChangeRequest> findByRequestedByAdminIdOrderByCreatedAtDesc(Long adminId);

    List<BingeChangeRequest> findByBingeIdAndRequestTypeAndStatus(
        Long bingeId, BingeChangeRequest.Type type, BingeChangeRequest.Status status);
}
