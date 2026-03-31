package com.skbingegalaxy.availability.controller;

import com.skbingegalaxy.availability.dto.*;
import com.skbingegalaxy.availability.service.AvailabilityService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService service;

    // ── Public endpoints ─────────────────────────────────────

    @GetMapping("/dates")
    public ResponseEntity<ApiResponse<List<DayAvailabilityDto>>> getAvailableDates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(service.getAvailability(from, to, clientDate)));
    }

    @GetMapping("/slots")
    public ResponseEntity<ApiResponse<DayAvailabilityDto>> getSlotsForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(service.getSlotsForDate(date)));
    }

    // ── Internal endpoint called by booking-service ──────────

    @GetMapping("/internal/check")
    public ResponseEntity<Boolean> checkSlotAvailable(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int startMinute,
            @RequestParam int durationMinutes) {
        return ResponseEntity.ok(service.isSlotAvailable(date, startMinute, durationMinutes));
    }

    // ── Admin endpoints ──────────────────────────────────────

    @GetMapping("/admin/blocked-dates")
    public ResponseEntity<ApiResponse<List<BlockedDateDto>>> getAllBlockedDates() {
        return ResponseEntity.ok(ApiResponse.ok(service.getAllBlockedDates()));
    }

    @GetMapping("/admin/blocked-slots")
    public ResponseEntity<ApiResponse<List<BlockedSlotDto>>> getAllBlockedSlots() {
        return ResponseEntity.ok(ApiResponse.ok(service.getAllBlockedSlots()));
    }

    @PostMapping("/admin/block-date")
    public ResponseEntity<ApiResponse<BlockedDateDto>> blockDate(
            @RequestBody BlockDateRequest request,
            @RequestHeader("X-User-Id") Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok("Date blocked", service.blockDate(request, adminId)));
    }

    @DeleteMapping("/admin/unblock-date")
    public ResponseEntity<ApiResponse<Void>> unblockDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.unblockDate(date);
        return ResponseEntity.ok(ApiResponse.ok("Date unblocked", null));
    }

    @PostMapping("/admin/block-slot")
    public ResponseEntity<ApiResponse<BlockedSlotDto>> blockSlot(
            @RequestBody BlockSlotRequest request,
            @RequestHeader("X-User-Id") Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok("Slot blocked", service.blockSlot(request, adminId)));
    }

    @DeleteMapping("/admin/unblock-slot")
    public ResponseEntity<ApiResponse<Void>> unblockSlot(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int startHour) {
        service.unblockSlot(date, startHour);
        return ResponseEntity.ok(ApiResponse.ok("Slot unblocked", null));
    }
}
