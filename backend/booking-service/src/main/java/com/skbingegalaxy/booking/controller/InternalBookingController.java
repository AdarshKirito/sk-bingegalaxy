package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Internal-only endpoints for service-to-service calls.
 * Protected by {@link com.skbingegalaxy.common.security.InternalApiAuthFilter}.
 */
@RestController
@RequestMapping("/api/v1/bookings/internal")
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingRepository bookingRepository;

    /**
     * Returns the total and collected amounts for a booking.
     * Used by payment-service to validate client-supplied payment amounts.
     */
    @GetMapping("/amount/{bookingRef}")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> getBookingAmount(
            @PathVariable String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));

        BigDecimal collected = booking.getCollectedAmount() != null
            ? booking.getCollectedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = booking.getTotalAmount().subtract(collected);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "totalAmount", booking.getTotalAmount(),
            "collectedAmount", collected,
            "remainingBalance", remaining
        )));
    }
}
