package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingRiskFlagDto;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingRiskFlag.Severity;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.BookingRiskFlagRepository;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingRiskEvaluator;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-facing endpoints for the booking risk-flag inbox (Item 23).
 *
 * <p>Tenancy: every operation is scoped to a binge the calling admin owns
 * (super-admins can see all). Path: {@code /api/v1/bookings/admin/risk-flags}.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/risk-flags")
@RequiredArgsConstructor
public class AdminRiskFlagController {

    private final BookingRiskEvaluator riskEvaluator;
    private final BookingRepository bookingRepository;
    private final BookingRiskFlagRepository riskFlagRepository;
    private final AdminBingeScopeService adminBingeScopeService;

    /** Paginated inbox for the operator dashboard. */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<BookingRiskFlagDto>>> list(
            @RequestParam("bingeId") Long bingeId,
            @RequestParam(value = "openOnly", defaultValue = "true") boolean openOnly,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "viewing risk flags");
        return ResponseEntity.ok(ApiResponse.ok(
            riskEvaluator.listForBinge(bingeId, openOnly, page, size)));
    }

    /** All flags (open + acknowledged) for one booking — drives the booking detail panel. */
    @GetMapping("/booking/{bookingRef}")
    public ResponseEntity<ApiResponse<List<BookingRiskFlagDto>>> listForBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        Booking b = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));
        adminBingeScopeService.requireBingeOwnership(b.getBingeId(), adminId, role, "viewing risk flags for booking");
        return ResponseEntity.ok(ApiResponse.ok(riskEvaluator.listForBooking(bookingRef)));
    }

    /** Acknowledge / dismiss a flag (with optional disposition note). */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<BookingRiskFlagDto>> acknowledge(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // Tenant guard: load the flag, then require the caller owns its binge.
        // Prevents cross-tenant id enumeration / forced-browsing on /{id}.
        Long bingeId = riskFlagRepository.findById(id)
            .map(f -> f.getBingeId())
            .orElseThrow(() -> new ResourceNotFoundException("BookingRiskFlag", "id", id));
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "acknowledging risk flag");
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok("Flag acknowledged",
            riskEvaluator.acknowledge(id, adminId, note)));
    }

    /** Manually flag a booking (operator-driven). */
    @PostMapping("/booking/{bookingRef}/manual")
    public ResponseEntity<ApiResponse<BookingRiskFlagDto>> createManual(
            @PathVariable String bookingRef,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        Booking b = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));
        adminBingeScopeService.requireBingeOwnership(b.getBingeId(), adminId, role, "creating manual risk flag");
        String severityRaw = body != null ? body.get("severity") : null;
        Severity severity = severityRaw == null ? Severity.MEDIUM : Severity.valueOf(severityRaw.toUpperCase());
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(ApiResponse.ok("Manual flag created",
            riskEvaluator.createManual(bookingRef, severity, reason, adminId)));
    }
}
