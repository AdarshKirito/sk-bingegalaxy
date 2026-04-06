package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
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

    private final PricingService pricingService;

    // ═══════════════════════════════════════════════════════════
    //  RATE CODE ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/rate-codes")
    public ResponseEntity<ApiResponse<List<RateCodeDto>>> getAllRateCodes() {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getAllRateCodes()));
    }

    @GetMapping("/rate-codes/active")
    public ResponseEntity<ApiResponse<List<RateCodeDto>>> getActiveRateCodes() {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getActiveRateCodes()));
    }

    @GetMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<RateCodeDto>> getRateCode(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getRateCodeById(id)));
    }

    @PostMapping("/rate-codes")
    public ResponseEntity<ApiResponse<RateCodeDto>> createRateCode(@Valid @RequestBody RateCodeSaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Rate code created", pricingService.createRateCode(request)));
    }

    @PutMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<RateCodeDto>> updateRateCode(@PathVariable Long id,
                                                                    @Valid @RequestBody RateCodeSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Rate code updated", pricingService.updateRateCode(id, request)));
    }

    @DeleteMapping("/rate-codes/{id}")
    public ResponseEntity<ApiResponse<Void>> toggleRateCode(@PathVariable Long id) {
        pricingService.toggleRateCode(id);
        return ResponseEntity.ok(ApiResponse.ok("Rate code toggled", null));
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER PRICING ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<CustomerPricingDto>> getCustomerPricing(@PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getCustomerPricing(customerId)));
    }

    @PostMapping("/customer")
    public ResponseEntity<ApiResponse<CustomerPricingDto>> saveCustomerPricing(
            @RequestBody CustomerPricingSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Customer pricing saved", pricingService.saveCustomerPricing(request)));
    }

    @PostMapping("/bulk-assign-rate-code")
    public ResponseEntity<ApiResponse<Integer>> bulkAssignRateCode(@RequestBody BulkRateCodeAssignRequest request) {
        int count = pricingService.bulkAssignRateCode(request);
        return ResponseEntity.ok(ApiResponse.ok(count + " customers updated", count));
    }

    // ═══════════════════════════════════════════════════════════
    //  PRICING RESOLUTION (admin preview)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/resolve/{customerId}")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> resolveCustomerPricing(@PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveCustomerPricing(customerId)));
    }

    @GetMapping("/resolve-rate-code/{rateCodeId}")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> resolveRateCodePricing(@PathVariable Long rateCodeId) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveRateCodePricing(rateCodeId)));
    }
}
