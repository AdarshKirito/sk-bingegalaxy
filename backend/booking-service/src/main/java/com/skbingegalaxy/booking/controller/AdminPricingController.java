package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings/admin/pricing")
@RequiredArgsConstructor
@Validated
public class AdminPricingController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final PricingService pricingService;

    private void validatePricingScope(Long adminId, String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
        pricingService.setCurrentAdminId(adminId);
    }

    // ═══════════════════════════════════════════════════════════
    //  RATE CODE ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/rate-codes")
    public ResponseEntity<ApiResponse<List<RateCodeDto>>> getAllRateCodes(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getAllRateCodes()));
    }

    @GetMapping("/rate-codes/active")
    public ResponseEntity<ApiResponse<List<RateCodeDto>>> getActiveRateCodes(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getActiveRateCodes()));
    }

    @GetMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<RateCodeDto>> getRateCode(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getRateCodeById(id)));
    }

    @PostMapping("/rate-codes")
    public ResponseEntity<ApiResponse<RateCodeDto>> createRateCode(
            @Valid @RequestBody RateCodeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Rate code created", pricingService.createRateCode(request)));
    }

    @PutMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<RateCodeDto>> updateRateCode(
            @PathVariable Long id,
            @Valid @RequestBody RateCodeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Rate code updated", pricingService.updateRateCode(id, request)));
    }

    @PatchMapping("/rate-codes/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleRateCode(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        pricingService.toggleRateCode(id);
        return ResponseEntity.ok(ApiResponse.ok("Rate code toggled", null));
    }

    @DeleteMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRateCode(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        pricingService.deleteRateCode(id);
        return ResponseEntity.ok(ApiResponse.ok("Rate code deleted", null));
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER PRICING ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<CustomerPricingDto>> getCustomerPricing(
            @PathVariable Long customerId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getCustomerPricing(customerId)));
    }

    @PostMapping("/customer")
    public ResponseEntity<ApiResponse<CustomerPricingDto>> saveCustomerPricing(
            @Valid @RequestBody CustomerPricingSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Customer pricing saved", pricingService.saveCustomerPricing(request)));
    }

    @DeleteMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<Void>> deleteCustomerPricing(
            @PathVariable Long customerId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        pricingService.deleteCustomerPricing(customerId);
        return ResponseEntity.ok(ApiResponse.ok("Customer pricing deleted", null));
    }

    @PostMapping("/bulk-assign-rate-code")
    public ResponseEntity<ApiResponse<Integer>> bulkAssignRateCode(
            @Valid @RequestBody BulkRateCodeAssignRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        int count = pricingService.bulkAssignRateCode(request);
        return ResponseEntity.ok(ApiResponse.ok(count + " customers updated", count));
    }

    @PatchMapping("/customer/{customerId}/member-label")
    public ResponseEntity<ApiResponse<Void>> updateMemberLabel(
            @PathVariable Long customerId,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        pricingService.updateMemberLabel(customerId, body.get("memberLabel"));
        return ResponseEntity.ok(ApiResponse.ok("Member label updated", null));
    }

    // ═══════════════════════════════════════════════════════════
    //  PRICING RESOLUTION (admin preview)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/resolve/{customerId}")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> resolveCustomerPricing(
            @PathVariable Long customerId,
            @RequestParam(value = "overrideRateCodeId", required = false) Long overrideRateCodeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        // With overrideRateCodeId, the response mirrors the precedence used at booking
        // creation (customer-specific > override > profile rate code > default), so the
        // wizard preview matches the actual charge. Without it, falls back to the plain
        // customer pricing resolver.
        if (overrideRateCodeId != null) {
            return ResponseEntity.ok(ApiResponse.ok(
                pricingService.resolveCustomerPricingWithOverride(customerId, overrideRateCodeId)));
        }
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveCustomerPricing(customerId)));
    }

    @GetMapping("/resolve-rate-code/{rateCodeId}")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> resolveRateCodePricing(
            @PathVariable Long rateCodeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveRateCodePricing(rateCodeId)));
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER DETAIL (rate code audit + reservations)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/customer-detail/{customerId}")
    public ResponseEntity<ApiResponse<CustomerDetailDto>> getCustomerDetail(
            @PathVariable Long customerId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getCustomerDetail(customerId)));
    }
}
