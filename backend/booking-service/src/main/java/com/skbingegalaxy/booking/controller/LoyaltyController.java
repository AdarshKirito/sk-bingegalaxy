package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.LoyaltyAccountDto;
import com.skbingegalaxy.booking.service.LoyaltyService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * System-level loyalty endpoints — no binge selection required.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    @GetMapping("/loyalty")
    public ResponseEntity<ApiResponse<LoyaltyAccountDto>> getMyLoyalty(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(loyaltyService.getAccount(userId)));
    }
}
