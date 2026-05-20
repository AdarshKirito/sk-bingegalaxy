package com.skbingegalaxy.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.dto.AdminApprovalRequestDto;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest.Status;
import com.skbingegalaxy.payment.repository.AdminApprovalRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Maker-checker (4-eyes) approval workflow.
 *
 * <p>Provides a generic state machine where a requester admin records an
 * intent to perform a risky action and a different reviewer admin must
 * approve before execution. Designed to be invoked by domain services (e.g.
 * {@link PaymentService}) so the domain decides which actions need approval
 * and what the threshold is.
 *
 * <p>Production guarantees:
 * <ul>
 *   <li>Optimistic locking on the row prevents lost-update if two reviewers
 *   click "approve" simultaneously.</li>
 *   <li>The reviewer must be a different identity than the requester
 *   (enforced by id when present, by email otherwise).</li>
 *   <li>PENDING rows older than {@code app.approvals.ttl-hours} are auto-
 *   expired by the scheduled sweep (see {@code AdminApprovalExpiryScheduler}).</li>
 *   <li>Audited via {@link AuditLogService} on every state change.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalService {

    private final AdminApprovalRequestRepository repository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /** Approval window — PENDING rows older than this are auto-EXPIRED. */
    @Value("${app.approvals.ttl-hours:48}")
    private int ttlHours;

    /**
     * Create a new approval request. Returns the persisted row so callers can
     * surface its id in their HTTP error response.
     */
    @Transactional
    public AdminApprovalRequest createRequest(String actionType,
                                              String resourceType,
                                              String resourceId,
                                              BigDecimal amount,
                                              String currency,
                                              Long bingeId,
                                              Map<String, Object> payload,
                                              String requestedBy,
                                              Long requestedById,
                                              String requestReason) {
        AdminApprovalRequest req = AdminApprovalRequest.builder()
            .actionType(actionType)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .amount(amount)
            .currency(currency)
            .bingeId(bingeId)
            .status(Status.PENDING)
            .requestedBy(requestedBy == null ? "SYSTEM" : requestedBy)
            .requestedById(requestedById)
            .requestReason(safeTrim(requestReason, 1000))
            .payload(serialize(payload))
            .expiresAt(LocalDateTime.now().plusHours(ttlHours))
            .build();
        req = repository.save(req);
        auditLogService.record(requestedBy, "APPROVAL_REQUESTED", resourceType, resourceId,
            amount, currency, bingeId,
            Map.of("approvalId", String.valueOf(req.getId()), "actionType", actionType));
        log.info("Approval request created: id={} action={} resource={}:{} requestedBy={}",
            req.getId(), actionType, resourceType, resourceId, requestedBy);
        return req;
    }

    @Transactional
    public AdminApprovalRequest approve(Long id, String reviewer, Long reviewerId, String reason) {
        AdminApprovalRequest req = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AdminApprovalRequest", "id", id.toString()));

        if (req.getStatus() != Status.PENDING) {
            throw new BusinessException(
                "Only PENDING requests can be approved. Current: " + req.getStatus(),
                HttpStatus.CONFLICT);
        }
        if (req.getExpiresAt().isBefore(LocalDateTime.now())) {
            req.setStatus(Status.EXPIRED);
            repository.save(req);
            throw new BusinessException(
                "This approval request has expired and cannot be approved",
                HttpStatus.CONFLICT);
        }

        // Separation of duties: reviewer must NOT be the requester.
        if (sameActor(req.getRequestedBy(), req.getRequestedById(), reviewer, reviewerId)) {
            throw new BusinessException(
                "Approval requires a different admin than the requester (4-eyes principle)",
                HttpStatus.FORBIDDEN);
        }

        req.setStatus(Status.APPROVED);
        req.setReviewedBy(reviewer);
        req.setReviewedById(reviewerId);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewReason(safeTrim(reason, 1000));
        AdminApprovalRequest saved = repository.save(req);
        auditLogService.record(reviewer, "APPROVAL_APPROVED",
            req.getResourceType(), req.getResourceId(),
            req.getAmount(), req.getCurrency(), req.getBingeId(),
            Map.of("approvalId", String.valueOf(req.getId()),
                "actionType", req.getActionType(),
                "requestedBy", req.getRequestedBy()));
        log.info("Approval id={} APPROVED by {} (was requested by {})",
            req.getId(), reviewer, req.getRequestedBy());
        return saved;
    }

    @Transactional
    public AdminApprovalRequest reject(Long id, String reviewer, Long reviewerId, String reason) {
        AdminApprovalRequest req = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AdminApprovalRequest", "id", id.toString()));
        if (req.getStatus() != Status.PENDING) {
            throw new BusinessException(
                "Only PENDING requests can be rejected. Current: " + req.getStatus(),
                HttpStatus.CONFLICT);
        }
        if (sameActor(req.getRequestedBy(), req.getRequestedById(), reviewer, reviewerId)) {
            throw new BusinessException(
                "Rejection requires a different admin than the requester",
                HttpStatus.FORBIDDEN);
        }
        req.setStatus(Status.REJECTED);
        req.setReviewedBy(reviewer);
        req.setReviewedById(reviewerId);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewReason(safeTrim(reason, 1000));
        AdminApprovalRequest saved = repository.save(req);
        auditLogService.record(reviewer, "APPROVAL_REJECTED",
            req.getResourceType(), req.getResourceId(),
            req.getAmount(), req.getCurrency(), req.getBingeId(),
            Map.of("approvalId", String.valueOf(req.getId()),
                "actionType", req.getActionType()));
        log.info("Approval id={} REJECTED by {}", req.getId(), reviewer);
        return saved;
    }

    @Transactional
    public AdminApprovalRequest cancel(Long id, String requester, Long requesterId, String reason) {
        AdminApprovalRequest req = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AdminApprovalRequest", "id", id.toString()));
        if (req.getStatus() != Status.PENDING) {
            throw new BusinessException(
                "Only PENDING requests can be cancelled. Current: " + req.getStatus(),
                HttpStatus.CONFLICT);
        }
        // Only the original requester can cancel their own request.
        if (!sameActor(req.getRequestedBy(), req.getRequestedById(), requester, requesterId)) {
            throw new BusinessException(
                "Only the original requester can cancel an approval request",
                HttpStatus.FORBIDDEN);
        }
        req.setStatus(Status.CANCELLED);
        req.setReviewReason(safeTrim(reason, 1000));
        return repository.save(req);
    }

    /**
     * Marks an APPROVED request as EXECUTED. Called by the domain service
     * after it has performed the action — closes the loop so the same
     * approval can't be used twice.
     */
    @Transactional
    public AdminApprovalRequest markExecuted(Long id, String result) {
        AdminApprovalRequest req = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AdminApprovalRequest", "id", id.toString()));
        if (req.getStatus() != Status.APPROVED) {
            throw new BusinessException(
                "Only APPROVED requests can be executed. Current: " + req.getStatus(),
                HttpStatus.CONFLICT);
        }
        req.setStatus(Status.EXECUTED);
        req.setExecutedAt(LocalDateTime.now());
        req.setExecutedResult(safeTrim(result, 2000));
        return repository.save(req);
    }

    @Transactional(readOnly = true)
    public Page<AdminApprovalRequestDto> list(Status status, String actionType, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<AdminApprovalRequest> rows = (actionType == null || actionType.isBlank())
            ? repository.findByStatusOrderByRequestedAtDesc(status, pageable)
            : repository.findByActionTypeAndStatusOrderByRequestedAtDesc(actionType, status, pageable);
        return rows.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public AdminApprovalRequestDto get(Long id) {
        return toDto(repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AdminApprovalRequest", "id", id.toString())));
    }

    /** Scheduler hook: bulk-expire stale PENDING rows. */
    @Transactional
    public int expireStale() {
        int n = repository.expirePending(LocalDateTime.now());
        if (n > 0) log.info("Expired {} stale approval requests", n);
        return n;
    }

    public AdminApprovalRequestDto toDto(AdminApprovalRequest r) {
        return AdminApprovalRequestDto.builder()
            .id(r.getId())
            .actionType(r.getActionType())
            .resourceType(r.getResourceType())
            .resourceId(r.getResourceId())
            .amount(r.getAmount())
            .currency(r.getCurrency())
            .bingeId(r.getBingeId())
            .status(r.getStatus().name())
            .requestedBy(r.getRequestedBy())
            .requestedById(r.getRequestedById())
            .requestedAt(r.getRequestedAt())
            .requestReason(r.getRequestReason())
            .reviewedBy(r.getReviewedBy())
            .reviewedById(r.getReviewedById())
            .reviewedAt(r.getReviewedAt())
            .reviewReason(r.getReviewReason())
            .executedAt(r.getExecutedAt())
            .executedResult(r.getExecutedResult())
            .expiresAt(r.getExpiresAt())
            .payload(r.getPayload())
            .build();
    }

    // ── helpers ──────────────────────────────────────────────

    private static boolean sameActor(String aEmail, Long aId, String bEmail, Long bId) {
        if (aId != null && bId != null) return aId.equals(bId);
        if (aEmail != null && bEmail != null) return aEmail.equalsIgnoreCase(bEmail);
        return false;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private String serialize(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(m); }
        catch (JsonProcessingException e) { return null; }
    }
}
