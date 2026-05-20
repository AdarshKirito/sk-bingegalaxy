package com.skbingegalaxy.payment.repository;

import com.skbingegalaxy.payment.entity.AdminApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AdminApprovalRequestRepository
        extends JpaRepository<AdminApprovalRequest, Long> {

    Page<AdminApprovalRequest> findByStatusOrderByRequestedAtDesc(
            AdminApprovalRequest.Status status, Pageable pageable);

    Page<AdminApprovalRequest> findByActionTypeAndStatusOrderByRequestedAtDesc(
            String actionType, AdminApprovalRequest.Status status, Pageable pageable);

    /**
     * Bulk-fail any approval requests whose TTL has passed without a decision.
     * Run by a scheduler. Safe to run frequently — uses the indexed status +
     * expires_at columns and only touches PENDING rows.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE AdminApprovalRequest a SET a.status = 'EXPIRED' " +
           "WHERE a.status = 'PENDING' AND a.expiresAt < :now")
    int expirePending(@Param("now") LocalDateTime now);

    List<AdminApprovalRequest> findByResourceTypeAndResourceIdAndStatus(
            String resourceType, String resourceId, AdminApprovalRequest.Status status);
}
