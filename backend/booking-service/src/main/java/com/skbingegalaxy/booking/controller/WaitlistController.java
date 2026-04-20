package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.JoinWaitlistRequest;
import com.skbingegalaxy.booking.dto.WaitlistEntryDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.WaitlistService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final WaitlistService waitlistService;

    @ModelAttribute
    void validateSelectedBinge() {
        adminBingeScopeService.requireSelectedBinge("accessing waitlist");
    }

    // ── Customer endpoints ───────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<WaitlistEntryDto>> joinWaitlist(
            @Valid @RequestBody JoinWaitlistRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Name", defaultValue = "Customer") String name,
            @RequestHeader(value = "X-User-Phone", defaultValue = "") String phone) {
        WaitlistEntryDto entry = waitlistService.joinWaitlist(request, userId, name, email, phone);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Added to waitlist", entry));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<ApiResponse<Void>> leaveWaitlist(
            @PathVariable Long entryId,
            @RequestHeader("X-User-Id") Long userId) {
        waitlistService.leaveWaitlist(entryId, userId);
        return ResponseEntity.ok(ApiResponse.ok("Removed from waitlist", null));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<WaitlistEntryDto>>> getMyWaitlist(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(waitlistService.getMyWaitlistEntries(userId)));
    }

    // ── Admin endpoints ──────────────────────────────────────

    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<List<WaitlistEntryDto>>> getWaitlistForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "viewing waitlist");
        return ResponseEntity.ok(ApiResponse.ok(waitlistService.getWaitlistForDate(date)));
    }

    @GetMapping("/admin/count")
    public ResponseEntity<ApiResponse<Long>> getWaitlistCount(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "viewing waitlist count");
        return ResponseEntity.ok(ApiResponse.ok(waitlistService.getWaitlistCount(date)));
    }
}
