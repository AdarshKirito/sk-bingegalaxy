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
    public ResponseEntity<ApiResponse<List<PublicBingeDto>>> getAllActiveBinges() {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAllActiveBinges()));
    }

    /**
     * Public: venues nearest to a point, nearest-first, each annotated with
     * {@code distanceKm}. Powers the "find venues near me" experience — the SPA
     * passes the browser-supplied geolocation. Declared before {@code /binges/{id}}
     * so the literal {@code nearby} path takes precedence over the id pattern.
     *
     * @param lat      WGS-84 latitude  (-90..90), required
     * @param lng      WGS-84 longitude (-180..180), required
     * @param radiusKm search radius in km; clamped to {@code [0, 500]}, default 50
     * @param limit    max results; clamped to {@code [1, 100]}, default 20
     */
    @GetMapping("/binges/nearby")
    public ResponseEntity<ApiResponse<List<PublicBingeDto>>> getNearbyBinges(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false, defaultValue = "50") double radiusKm,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
            bingeService.getNearbyBinges(lat, lng, radiusKm, limit)));
    }

    // ── Public: get single binge ─────────────────────────────
    @GetMapping("/binges/{id}")
    public ResponseEntity<ApiResponse<PublicBingeDto>> getBinge(@PathVariable Long id) {
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
    // Optional body {"taxesEnabled": bool, "disabledModules": [..], "accessRemarks": ".."}
    // — the approval dialog confirms the venue's timezone + currency, records the
    // super-admin's tax decision, AND sets which binge-operation modules the owning
    // admin may use from day one (V71 permission matrix; unlisted modules stay enabled).
    @PostMapping("/admin/binges/{id}/approve")
    public ResponseEntity<ApiResponse<BingeDto>> approveBinge(
            @PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, Object> body,
            @RequestHeader("X-User-Id") Long superAdminId,
            @RequestHeader("X-User-Role") String role) {
        Boolean taxesEnabled = null;
        java.util.List<String> disabledModules = null;
        String accessRemarks = null;
        String timezoneOverride = null;
        String countryOverride = null;
        if (body != null) {
            if (body.get("taxesEnabled") instanceof Boolean b) taxesEnabled = b;
            if (body.get("timezone") instanceof String tz && !tz.isBlank()) timezoneOverride = tz;
            if (body.get("country") instanceof String c && !c.isBlank()) countryOverride = c;
            if (body.get("disabledModules") instanceof java.util.List<?> list) {
                disabledModules = list.stream().map(String::valueOf).toList();
            }
            if (body.get("accessRemarks") instanceof String s && !s.isBlank()) accessRemarks = s;
        }
        return ResponseEntity.ok(ApiResponse.ok(
            "Binge approved",
            bingeService.approveBinge(id, superAdminId, role, taxesEnabled, disabledModules,
                accessRemarks, timezoneOverride, countryOverride)));
    }

    // ── Super-Admin: master tax switch for a venue ─────────────────────
    @PatchMapping("/admin/binges/{id}/taxes-enabled")
    public ResponseEntity<ApiResponse<BingeDto>> setTaxesEnabled(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        return ResponseEntity.ok(ApiResponse.ok(
            "Venue taxes " + (enabled ? "enabled" : "disabled"),
            bingeService.setTaxesEnabled(id, enabled, userId, role)));
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

    // ── Admin: re-request approval for a rejected binge ────────────────
    @PostMapping("/admin/binges/{id}/resubmit")
    public ResponseEntity<ApiResponse<BingeDto>> resubmitBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Binge re-submitted for approval", bingeService.resubmitBinge(id, adminId, role)));
    }

    // ── Admin: update binge ──────────────────────────────────
    @PutMapping("/admin/binges/{id}")
    public ResponseEntity<ApiResponse<BingeDto>> updateBinge(
            @PathVariable Long id,
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Authority-Delegated", required = false) String delegatedHeader) {
        // A delegated super-admin holds broad authority via Authority-Handover but is
        // NOT a native super-admin — timezone changes still require an explicit grant.
        boolean delegated = "true".equalsIgnoreCase(delegatedHeader);
        return ResponseEntity.ok(ApiResponse.ok("Binge updated", bingeService.updateBinge(id, request, adminId, role, delegated)));
    }

    /**
     * A regular admin requests that a super-admin move this binge to a different country
     * (which re-derives its currency). Persists a PENDING change request (audit trail)
     * and notifies super-admins, who approve/reject from the review panel.
     * Body: {@code {"country":"US","reason":"..."}}.
     */
    @PostMapping("/admin/binges/{id}/country-request")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>> requestCountryChange(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        var dto = bingeService.requestCountryChange(id,
            body == null ? null : body.get("country"),
            body == null ? null : body.get("reason"),
            adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(
            "Country-change request sent to super-admins for review", dto));
    }

    /**
     * A regular admin reports that this venue's auto-derived timezone is wrong.
     * The admin cannot set the zone directly (the picker is read-only for them);
     * this raises a PENDING review for a super-admin to resolve.
     * Body: {@code {"timezone":"America/New_York" (optional suggestion), "reason":"..."}}.
     */
    @PostMapping("/admin/binges/{id}/timezone-request")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>> requestTimezoneChange(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        var dto = bingeService.requestTimezoneChange(id,
            body == null ? null : body.get("timezone"),
            body == null ? null : body.get("reason"),
            adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(
            "Timezone review sent to super-admins for resolution", dto));
    }

    // ── Binge change requests (country-change state machine) ─────────────
    // Gateway already requires ADMIN/SUPER_ADMIN for /admin/**; the service layer
    // additionally scopes admins to their own requests and gates decisions to
    // SUPER_ADMIN (requireSuperAdminRole) — defence in depth like the rest of
    // this controller.

    /** Super-admin: all requests (optional ?status=PENDING). Admin: own requests. */
    @GetMapping("/admin/binges/change-requests")
    public ResponseEntity<ApiResponse<java.util.List<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>>> listChangeRequests(
            @RequestParam(required = false) String status,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.listChangeRequests(userId, role, status)));
    }

    /** SUPER-ADMIN: approve — applies country+currency atomically and notifies the requester. */
    @PostMapping("/admin/binges/change-requests/{requestId}/approve")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>> approveChangeRequest(
            @PathVariable Long requestId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Change request approved",
            bingeService.approveChangeRequest(requestId, userId, role,
                body == null ? null : body.get("note"),
                // For a timezone review the super-admin sends the zone to apply here.
                body == null ? null : body.get("timezone"))));
    }

    /** SUPER-ADMIN: reject with an optional note; the requester is notified. */
    @PostMapping("/admin/binges/change-requests/{requestId}/reject")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>> rejectChangeRequest(
            @PathVariable Long requestId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Change request rejected",
            bingeService.rejectChangeRequest(requestId, userId, role,
                body == null ? null : body.get("note"))));
    }

    /** Requester (or super-admin) withdraws a pending request. */
    @PostMapping("/admin/binges/change-requests/{requestId}/cancel")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.dto.BingeChangeRequestDto>> cancelChangeRequest(
            @PathVariable Long requestId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Change request cancelled",
            bingeService.cancelChangeRequest(requestId, userId, role)));
    }

    // ── Native Super-Admin only: bulk-assign a timezone to many venues ──
    // Unlike updateBinge above, this console is intentionally NOT delegable —
    // Authority-Handover grants (even X-Authority-Delegated=true SUPER_ADMIN) are
    // rejected outright rather than falling through to the per-binge grant check,
    // matching the page-level access rule ("only super admin").
    @PostMapping("/admin/binges/bulk-timezone")
    public ResponseEntity<ApiResponse<BulkBingeTimezoneAssignResult>> bulkAssignTimezone(
            @Valid @RequestBody BulkBingeTimezoneAssignRequest request,
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Authority-Delegated", required = false) String delegatedHeader) {
        boolean delegated = "true".equalsIgnoreCase(delegatedHeader);
        if (!"SUPER_ADMIN".equalsIgnoreCase(role) || delegated) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only a native super-admin can bulk-assign venue timezones", HttpStatus.FORBIDDEN);
        }
        BulkBingeTimezoneAssignResult result = bingeService.bulkAssignTimezone(request, role, delegated);
        String message = result.getNotFoundBingeIds().isEmpty()
            ? result.getUpdatedCount() + " venue(s) updated"
            : result.getUpdatedCount() + " venue(s) updated, " + result.getNotFoundBingeIds().size() + " not found";
        return ResponseEntity.ok(ApiResponse.ok(message, result));
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
