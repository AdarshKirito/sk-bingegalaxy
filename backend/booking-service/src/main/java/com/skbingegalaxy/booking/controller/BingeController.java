package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.service.BingeService;
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
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BingeController {

    private final BingeService bingeService;

    // ── Public: list all active binges (for user selection) ──
    @GetMapping("/binges")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getAllActiveBinges() {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAllActiveBinges()));
    }

    // ── Public: get single binge ─────────────────────────────
    @GetMapping("/binges/{id}")
    public ResponseEntity<ApiResponse<BingeDto>> getBinge(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getBingeById(id)));
    }

    // ── Admin: list admin's binges ───────────────────────────
    @GetMapping("/admin/binges")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getAdminBinges(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getAdminBinges(adminId, role)));
    }

    // ── Super-Admin: get binges by a specific admin ──────────
    @GetMapping("/admin/binges/by-admin/{adminId}")
    public ResponseEntity<ApiResponse<List<BingeDto>>> getBingesByAdmin(@PathVariable Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok(bingeService.getBingesByAdminId(adminId)));
    }

    // ── Admin: create binge ──────────────────────────────────
    @PostMapping("/admin/binges")
    public ResponseEntity<ApiResponse<BingeDto>> createBinge(
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Binge created", bingeService.createBinge(request, adminId, clientDate)));
    }

    // ── Admin: update binge ──────────────────────────────────
    @PutMapping("/admin/binges/{id}")
    public ResponseEntity<ApiResponse<BingeDto>> updateBinge(
            @PathVariable Long id,
            @Valid @RequestBody BingeSaveRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok("Binge updated", bingeService.updateBinge(id, request, adminId, role)));
    }

    // ── Admin: toggle active ─────────────────────────────────
    @PatchMapping("/admin/binges/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        bingeService.toggleBinge(id, adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Binge toggled", null));
    }

    // ── Admin: delete binge ──────────────────────────────────
    @DeleteMapping("/admin/binges/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBinge(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        bingeService.deleteBinge(id, adminId, role);
        return ResponseEntity.ok(ApiResponse.ok("Binge deleted", null));
    }
}
