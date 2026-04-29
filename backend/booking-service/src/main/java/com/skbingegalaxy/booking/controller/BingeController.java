package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BingeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.CancellationTierService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class BingeController {

    private final BingeService bingeService;
    private final BookingService bookingService;
    private final CancellationTierService cancellationTierService;
    private final AdminBingeScopeService adminBingeScopeService;

    // ── Public: list all active binges (for user selection) ──
    @GetMapping("/binges")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getAllActiveBinges() {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAllActiveBinges()));
    }

    // ── Public: get single binge ─────────────────────────────
    @GetMapping("/binges/{id}")
    public ResponseEntity<ApiResponse<BingeDto>> getBinge(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getBingeById(id)));
    }

    @GetMapping("/binges/{id}/customer-dashboard")
    public ResponseEntity<ApiResponse<CustomerDashboardExperienceDto>> getCustomerDashboardExperience(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getCustomerDashboardExperience(id)));
    }

    @GetMapping("/binges/{id}/customer-about")
    public ResponseEntity<ApiResponse<CustomerAboutExperienceDto>> getCustomerAboutExperience(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getCustomerAboutExperience(id)));
    }

    // ── Public: binge review summary (avg rating, count, distribution) ──
    @GetMapping("/binges/{id}/reviews/summary")
    public ResponseEntity<ApiResponse<BingeReviewSummaryDto>> getBingeReviewSummary(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBingeReviewSummary(id)));
    }

    // ── Public: paginated customer reviews for a binge ──
    @GetMapping("/binges/{id}/reviews")
    public ResponseEntity<ApiResponse<Page<BookingReviewDto>>> getBingePublicReviews(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            bookingService.getBingePublicReviews(id, PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createdAt")))));
    }

    // ── Admin: list admin's binges ───────────────────────────
    @GetMapping("/admin/binges")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getAdminBinges(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAdminBinges(adminId, role)));
    }

    // ── Super-Admin: get binges by a specific admin ──────────
    @GetMapping("/admin/binges/by-admin/{adminId}")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getBingesByAdmin(
            @PathVariable Long adminId,
            @RequestHeader("X-User-Role") String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only super-admins can view other admins' binges", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getBingesByAdminId(adminId)));
    }

    // ── Admin: create binge ──────────────────────────────────
    @PostMapping("/admin/binges")
    public ResponseEntity<ApiResponse<BingeDto>> createBinge(
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        BingeDto created = bingeService.createBinge(request, adminId, role, clientDate);
        String message = "PENDING_APPROVAL".equals(created.getStatus())
            ? "Binge submitted for super-admin approval"
            : "Binge created";
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(message, created));
    }

    // ── Super-Admin: list pending binge approval requests ───────────────
    @GetMapping("/admin/binges/pending")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getPendingBinges(
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getPendingBinges(role)));
    }

    // ── Super-Admin: approve a pending binge ──────────────────────────
    @PostMapping("/admin/binges/{id}/approve")
    public ResponseEntity<ApiResponse<BingeDto>> approveBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long superAdminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Binge approved", bingeService.approveBinge(id, superAdminId, role)));
    }

    // ── Super-Admin: reject a pending binge ────────────────────────────
    @PostMapping("/admin/binges/{id}/reject")
    public ResponseEntity<ApiResponse<BingeDto>> rejectBinge(
            @PathVariable Long id,
            @RequestBody(required = false) BingeRejectRequest body,
            @RequestHeader("X-User-Id") Long superAdminId,
            @RequestHeader("X-User-Role") String role) {
        String reason = body != null ? body.getReason() : null;
        return ResponseEntity.ok(ApiResponse.ok(
            "Binge rejected", bingeService.rejectBinge(id, superAdminId, role, reason)));
    }

    /** Inline body for the reject endpoint. */
    @lombok.Data
    public static class BingeRejectRequest {
        private String reason;
    }

    // ── Admin: update binge ──────────────────────────────────
    @PutMapping("/admin/binges/{id}")
    public ResponseEntity<ApiResponse<BingeDto>> updateBinge(
            @PathVariable Long id,
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Binge updated", bingeService.updateBinge(id, request, adminId, role)));
    }

    @GetMapping("/admin/binges/{id}/customer-dashboard")
    public ResponseEntity<ApiResponse<CustomerDashboardExperienceDto>> getAdminCustomerDashboardExperience(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAdminCustomerDashboardExperience(id, adminId, role)));
    }

    @GetMapping("/admin/binges/{id}/customer-about")
    public ResponseEntity<ApiResponse<CustomerAboutExperienceDto>> getAdminCustomerAboutExperience(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAdminCustomerAboutExperience(id, adminId, role)));
    }

    @PutMapping("/admin/binges/{id}/customer-dashboard")
    public ResponseEntity<ApiResponse<CustomerDashboardExperienceDto>> updateCustomerDashboardExperience(
            @PathVariable Long id,
            @Valid @RequestBody CustomerDashboardExperienceDto request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Customer dashboard experience updated",
            bingeService.updateCustomerDashboardExperience(id, request, adminId, role)));
    }

            @PutMapping("/admin/binges/{id}/customer-about")
            public ResponseEntity<ApiResponse<CustomerAboutExperienceDto>> updateCustomerAboutExperience(
                @PathVariable Long id,
                @Valid @RequestBody CustomerAboutExperienceDto request,
                @RequestHeader("X-User-Id") Long adminId,
                @RequestHeader("X-User-Role") String role) {
            return ResponseEntity.ok(ApiResponse.ok(
                "Customer about experience updated",
                bingeService.updateCustomerAboutExperience(id, request, adminId, role)));
            }

    // ── Admin: toggle active ─────────────────────────────────
    @PatchMapping("/admin/binges/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        bingeService.toggleBinge(id, adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Binge toggled", null));
    }

    // ── Admin: delete binge ──────────────────────────────────
    @DeleteMapping("/admin/binges/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        bingeService.deleteBinge(id, adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Binge deleted", null));
    }

    // ── Admin: cancellation tiers ────────────────────────────
    @GetMapping("/admin/binges/{id}/cancellation-tiers")
    public ResponseEntity<ApiResponse<List<CancellationTierDto>>> getCancellationTiers(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(id, adminId, role, "viewing cancellation tiers");
        return ResponseEntity.ok(ApiResponse.ok(cancellationTierService.getTiers(id)));
    }

    @PutMapping("/admin/binges/{id}/cancellation-tiers")
    public ResponseEntity<ApiResponse<List<CancellationTierDto>>> saveCancellationTiers(
            @PathVariable Long id,
            @Valid @RequestBody CancellationTierSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(id, adminId, role, "saving cancellation tiers");
        return ResponseEntity.ok(ApiResponse.ok("Cancellation tiers saved",
            cancellationTierService.saveTiers(id, request)));
    }

    // ── Public: cancellation tiers for a binge (for customer display) ──
    @GetMapping("/binges/{id}/cancellation-tiers")
    public ResponseEntity<ApiResponse<List<CancellationTierDto>>> getPublicCancellationTiers(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(cancellationTierService.getTiers(id)));
    }

    // ── Admin: cancellation policy (binge-level freeze + refund flags) ──
    @GetMapping("/admin/binges/{id}/cancellation-policy")
    public ResponseEntity<ApiResponse<CancellationPolicyDto>> getCancellationPolicy(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(id, adminId, role, "viewing cancellation policy");
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getCancellationPolicy(id)));
    }

    @PutMapping("/admin/binges/{id}/cancellation-policy")
    public ResponseEntity<ApiResponse<CancellationPolicyDto>> saveCancellationPolicy(
            @PathVariable Long id,
            @Valid @RequestBody CancellationPolicyDto request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(id, adminId, role, "updating cancellation policy");
        return ResponseEntity.ok(ApiResponse.ok("Cancellation policy saved",
            bingeService.saveCancellationPolicy(id, request)));
    }
}
