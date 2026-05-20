package com.skbingegalaxy.payment.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.dto.AdminApprovalRequestDto;
import com.skbingegalaxy.payment.entity.AdminApprovalRequest;
import com.skbingegalaxy.payment.service.AdminApprovalService;
import com.skbingegalaxy.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoints for the maker-checker (4-eyes) approval workflow.
 *
 * <ul>
 *   <li>{@code GET    /admin/approvals?status=PENDING&action=REFUND_RETRY}</li>
 *   <li>{@code GET    /admin/approvals/{id}}</li>
 *   <li>{@code POST   /admin/approvals/{id}/approve} — different admin only</li>
 *   <li>{@code POST   /admin/approvals/{id}/reject}</li>
 *   <li>{@code POST   /admin/approvals/{id}/cancel}  — original requester only</li>
 *   <li>{@code POST   /admin/approvals/{id}/execute-refund-retry} — domain action;
 *       fails if the request is not APPROVED for action {@code REFUND_RETRY}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/payments/admin/approvals")
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalController {

    private final AdminApprovalService approvalService;
    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        AdminApprovalRequest.Status s;
        try {
            s = AdminApprovalRequest.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid status: " + status));
        }
        Page<AdminApprovalRequestDto> result = approvalService.list(s, action, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("total", result.getTotalElements());
        body.put("rows", result.getContent());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminApprovalRequestDto>> get(
            @PathVariable Long id,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        return ResponseEntity.ok(ApiResponse.ok(approvalService.get(id)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<AdminApprovalRequestDto>> approve(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-User-Id") Long reviewerId,
            @RequestHeader("X-User-Email") String reviewerEmail,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        String reason = body == null ? null : body.get("reason");
        AdminApprovalRequest req = approvalService.approve(id, reviewerEmail, reviewerId, reason);
        return ResponseEntity.ok(ApiResponse.ok("Approved", approvalService.toDto(req)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<AdminApprovalRequestDto>> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-User-Id") Long reviewerId,
            @RequestHeader("X-User-Email") String reviewerEmail,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        String reason = body == null ? null : body.get("reason");
        AdminApprovalRequest req = approvalService.reject(id, reviewerEmail, reviewerId, reason);
        return ResponseEntity.ok(ApiResponse.ok("Rejected", approvalService.toDto(req)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<AdminApprovalRequestDto>> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-User-Id") Long requesterId,
            @RequestHeader("X-User-Email") String requesterEmail,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        String reason = body == null ? null : body.get("reason");
        AdminApprovalRequest req = approvalService.cancel(id, requesterEmail, requesterId, reason);
        return ResponseEntity.ok(ApiResponse.ok("Cancelled", approvalService.toDto(req)));
    }

    /**
     * Execute the underlying refund-retry that was previously approved.
     * The executor must be the same admin who approved (or an admin different
     * from the requester — domain decides). Today we delegate to
     * {@link PaymentService#executeApprovedRefundRetry} which handles state
     * transition + audit + EXECUTED stamp.
     */
    @PostMapping("/{id}/execute-refund-retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> executeRefundRetry(
            @PathVariable Long id,
            @RequestHeader("X-User-Email") String adminEmail,
            @RequestHeader("X-User-Role") String role) {
        ensureAdmin(role);
        Map<String, Object> result = paymentService.executeApprovedRefundRetry(id, adminEmail);
        return ResponseEntity.ok(ApiResponse.ok("Executed", result));
    }

    private static void ensureAdmin(String role) {
        if (role == null || !(role.equalsIgnoreCase("ADMIN") || role.equalsIgnoreCase("SUPER_ADMIN"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
