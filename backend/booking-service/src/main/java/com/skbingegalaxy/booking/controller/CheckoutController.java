package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.CheckoutPreviewRequest;
import com.skbingegalaxy.booking.dto.CheckoutPreviewResponse;
import com.skbingegalaxy.booking.dto.FxLockRequest;
import com.skbingegalaxy.booking.dto.FxLockResponse;
import com.skbingegalaxy.booking.service.CheckoutQuoteService;
import com.skbingegalaxy.booking.service.FxLockService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Production-grade checkout endpoints.
 *
 * <ul>
 *   <li>{@code POST /preview}  — quote a full price breakdown (4 currencies, taxes).</li>
 *   <li>{@code POST /lock-fx}  — lock the FX rate for {@code N} minutes; returns a token.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutQuoteService quoteService;
    private final FxLockService fxLockService;

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<CheckoutPreviewResponse>> preview(
            @RequestBody CheckoutPreviewRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Customer-Id", required = false) Long legacyCustomerId) {
        Long customerId = userId != null ? userId : legacyCustomerId;
        return ResponseEntity.ok(ApiResponse.ok(quoteService.preview(request, customerId)));
    }

    @PostMapping("/lock-fx")
    public ResponseEntity<ApiResponse<FxLockResponse>> lockFx(
            @RequestBody FxLockRequest req,
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId) {
        FxLockResponse out = fxLockService.lockFx(
            req.getFromCurrency(), req.getToCurrency(),
            req.getBaseAmount(), req.getTtlMinutes(),
            customerId, null);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }
}
