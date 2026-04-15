package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.BingeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.CancellationTierService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
    public ResponseEntity<ApiResponse<List<BingeDto>>> getBingesByAdmin(@PathVariable Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getBingesByAdminId(adminId)));
    }

    // ── Admin: create binge ──────────────────────────────────
    @PostMapping("/admin/binges")
    public ResponseEntity<ApiResponse<BingeDto>> createBinge(
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Binge created", bingeService.createBinge(request, adminId, clientDate)));
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
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(cancellationTierService.getTiers(id)));
    }

    @PutMapping("/admin/binges/{id}/cancellation-tiers")
    public ResponseEntity<ApiResponse<List<CancellationTierDto>>> saveCancellationTiers(
            @PathVariable Long id,
            @Valid @RequestBody CancellationTierSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Cancellation tiers saved",
            cancellationTierService.saveTiers(id, request)));
    }

    // ── Public: cancellation tiers for a binge (for customer display) ──
    @GetMapping("/binges/{id}/cancellation-tiers")
    public ResponseEntity<ApiResponse<List<CancellationTierDto>>> getPublicCancellationTiers(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(cancellationTierService.getTiers(id)));
    }
}
