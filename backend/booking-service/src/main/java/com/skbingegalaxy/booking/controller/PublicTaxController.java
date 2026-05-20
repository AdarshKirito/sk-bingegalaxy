package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.TaxComputationResult;
import com.skbingegalaxy.booking.service.TaxService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Public endpoint that lets the customer's checkout UI preview the tax
 * breakdown for a given subtotal in the current binge. Returns the same
 * structure that's persisted on the booking after creation, so the UI can
 * show an accurate estimate before commit.
 */
@RestController
@RequestMapping("/api/v1/bookings/taxes")
@RequiredArgsConstructor
public class PublicTaxController {

    private final TaxService taxService;

    @GetMapping("/preview")
    public ResponseEntity<ApiResponse<TaxComputationResult>> preview(
            @RequestParam("subtotal") BigDecimal subtotal,
            @RequestParam(value = "baseAmount",   required = false) BigDecimal baseAmount,
            @RequestParam(value = "addOnAmount",  required = false) BigDecimal addOnAmount,
            @RequestParam(value = "guestAmount",  required = false) BigDecimal guestAmount) {
        Long bingeId = BingeContext.getBingeId();
        TaxComputationResult result = taxService.compute(
            bingeId,
            subtotal != null ? subtotal : BigDecimal.ZERO,
            baseAmount != null ? baseAmount : subtotal,
            addOnAmount != null ? addOnAmount : BigDecimal.ZERO,
            guestAmount != null ? guestAmount : BigDecimal.ZERO);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
