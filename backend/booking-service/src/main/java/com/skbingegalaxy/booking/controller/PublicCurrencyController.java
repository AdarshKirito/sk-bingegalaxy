package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.CurrencyRateDto;
import com.skbingegalaxy.booking.service.CurrencyService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public, unauthenticated endpoint that lets the SPA fetch the live list of
 * active currencies + their FX rates so the UI can render prices in the
 * customer's chosen currency.
 */
@RestController
@RequestMapping("/api/v1/bookings/currencies")
@RequiredArgsConstructor
public class PublicCurrencyController {

    private final CurrencyService currencyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CurrencyRateDto>>> active() {
        return ResponseEntity.ok(ApiResponse.ok(currencyService.listActive()));
    }
}
