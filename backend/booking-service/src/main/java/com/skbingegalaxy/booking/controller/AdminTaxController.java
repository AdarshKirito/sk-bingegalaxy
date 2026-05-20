package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.TaxRuleDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.TaxService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for managing tax rules.
 * <ul>
 *   <li>{@code /global/*} — only super admins (binge_id = NULL rules)</li>
 *   <li>{@code /} (default) — binge admins manage rules scoped to their selected binge</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/taxes")
@RequiredArgsConstructor
public class AdminTaxController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final TaxService taxService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TaxRuleDto>>> list(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(taxService.listRulesForCurrentBinge()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaxRuleDto>> create(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody TaxRuleDto request) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(taxService.createRule(request, false)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaxRuleDto>> update(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @Valid @RequestBody TaxRuleDto request) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
        // Defense-in-depth: a global rule (binge_id IS NULL) may only be
        // edited by a super-admin. The service layer also verifies binge
        // scoping for binge-owned rules.
        if (taxService.isGlobalRule(id) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super admins can edit global tax rules"));
        }
        return ResponseEntity.ok(ApiResponse.ok(taxService.updateRule(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id) {
        adminBingeScopeService.requireManagedBinge(adminId, role);
        if (taxService.isGlobalRule(id) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super admins can delete global tax rules"));
        }
        taxService.deleteRule(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Global (super-admin only) ──────────────────────────────────────────

    @GetMapping("/global")
    public ResponseEntity<ApiResponse<List<TaxRuleDto>>> listGlobal(
            @RequestHeader("X-User-Role") String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can view global tax rules"));
        }
        return ResponseEntity.ok(ApiResponse.ok(taxService.listGlobalRules()));
    }

    @PostMapping("/global")
    public ResponseEntity<ApiResponse<TaxRuleDto>> createGlobal(
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody TaxRuleDto request) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage global tax rules"));
        }
        return ResponseEntity.ok(ApiResponse.ok(taxService.createRule(request, true)));
    }
}
