package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.CreateFreezeRequest;
import com.skbingegalaxy.booking.dto.CustomerBingeFreezeDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.CustomerFreezeService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Customer + admin endpoints for booking-flow freezes.
 *
 * <ul>
 *   <li>Customer: see own active freezes.</li>
 *   <li>Admin / Super-admin: list freezes at a binge, lift a freeze, or
 *       apply a manual freeze.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class CustomerFreezeController {

    private final CustomerFreezeService freezeService;
    private final AdminBingeScopeService adminBingeScopeService;
    private final com.skbingegalaxy.booking.repository.CustomerBingeFreezeRepository freezeRepository;

    // ── Customer self-service ──────────────────────────────────────────────

    @GetMapping("/freezes/me")
    public ResponseEntity<ApiResponse<List<CustomerBingeFreezeDto>>> myFreezes(
            @RequestHeader("X-User-Id") Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(freezeService.listMyActiveFreezes(customerId)));
    }

    /** Returns the active freeze for the customer at the given binge, or null if none. */
    @GetMapping("/freezes/me/binge/{bingeId}")
    public ResponseEntity<ApiResponse<CustomerBingeFreezeDto>> myFreezeForBinge(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(freezeService.getMyActiveFreezeForBinge(customerId, bingeId)));
    }

    // ── Admin operations ───────────────────────────────────────────────────

    @GetMapping("/admin/freezes")
    public ResponseEntity<ApiResponse<List<CustomerBingeFreezeDto>>> listFreezes(
            @RequestParam(value = "bingeId") Long bingeId,
            @RequestParam(value = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "listing customer freezes");
        return ResponseEntity.ok(ApiResponse.ok(freezeService.listForBinge(bingeId, activeOnly)));
    }

    @PostMapping("/admin/freezes")
    public ResponseEntity<ApiResponse<CustomerBingeFreezeDto>> createFreeze(
            @Valid @RequestBody CreateFreezeRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(request.getBingeId(), adminId, role, "applying a manual freeze");
        return ResponseEntity.ok(ApiResponse.ok("Freeze applied",
            freezeService.createManualFreeze(request, adminId, role)));
    }

    @DeleteMapping("/admin/freezes/{id}")
    public ResponseEntity<ApiResponse<CustomerBingeFreezeDto>> liftFreeze(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // Cross-tenant guard: a binge admin can only lift freezes that belong to a
        // binge they own. Super-admins are allowed through requireBingeOwnership.
        var freeze = freezeRepository.findById(id)
            .orElseThrow(() -> new com.skbingegalaxy.common.exception.ResourceNotFoundException(
                "CustomerBingeFreeze", "id", id));
        adminBingeScopeService.requireBingeOwnership(freeze.getBingeId(), adminId, role, "lifting a customer freeze");
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(ApiResponse.ok("Freeze lifted",
            freezeService.liftFreeze(id, adminId, role, reason)));
    }
}
