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
 * Super-admin endpoints for managing tax rules.
 *
 * <p>Tax configuration is a platform-governance concern: incorrect rates directly
 * affect every customer's invoice and the business's tax compliance. As of the
 * governance tightening it is restricted to SUPER_ADMIN entirely — regular binge
 * admins no longer see or manage taxes (the "Taxes" nav entry is hidden for them
 * and the {@code /admin/taxes} route is super-admin-gated in the SPA). This
 * controller is the authoritative enforcement point; the UI changes are only a
 * convenience. Tax READS used by checkout/pricing live in {@code PublicTaxController}
 * and are unaffected.
 *
 * <ul>
 *   <li>{@code /global/*} — program-wide rules (binge_id = NULL)</li>
 *   <li>{@code /} (default) — rules scoped to the super-admin's selected binge</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/taxes")
@RequiredArgsConstructor
public class AdminTaxController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final TaxService taxService;

    /** Reject any caller that is not a super-admin. Returns null when allowed. */
    private static ResponseEntity<ApiResponse<Void>> denyIfNotSuperAdmin(String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super admins can manage tax rules"));
        }
        return null;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TaxRuleDto>>> list(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage tax rules"));
        }
        adminBingeScopeService.requireManagedBinge(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(taxService.listRulesForCurrentBinge()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaxRuleDto>> create(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody TaxRuleDto request) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage tax rules"));
        }
        adminBingeScopeService.requireManagedBinge(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(taxService.createRule(request, false)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaxRuleDto>> update(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @Valid @RequestBody TaxRuleDto request) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage tax rules"));
        }
        adminBingeScopeService.requireManagedBinge(adminId, role);
        return ResponseEntity.ok(ApiResponse.ok(taxService.updateRule(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id) {
        ResponseEntity<ApiResponse<Void>> denied = denyIfNotSuperAdmin(role);
        if (denied != null) return denied;
        adminBingeScopeService.requireManagedBinge(adminId, role);
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
