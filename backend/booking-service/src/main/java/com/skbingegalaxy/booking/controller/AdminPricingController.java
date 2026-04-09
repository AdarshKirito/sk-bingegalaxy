package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings/admin/pricing")
@RequiredArgsConstructor
public class AdminPricingController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final PricingService pricingService;

    private void validatePricingScope(Long adminId, String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
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
            @RequestBody CustomerPricingSaveRequest request,
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
            @RequestBody BulkRateCodeAssignRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
        int count = pricingService.bulkAssignRateCode(request);
        return ResponseEntity.ok(ApiResponse.ok(count + " customers updated", count));
    }

    // ═══════════════════════════════════════════════════════════
    //  PRICING RESOLUTION (admin preview)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/resolve/{customerId}")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> resolveCustomerPricing(
            @PathVariable Long customerId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        validatePricingScope(adminId, role);
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
}
