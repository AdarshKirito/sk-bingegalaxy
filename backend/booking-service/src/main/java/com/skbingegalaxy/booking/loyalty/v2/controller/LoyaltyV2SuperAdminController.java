package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyAdminService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Loyalty v2 — SUPER-ADMIN endpoints (program-wide scope).
 *
 * <p>Served at {@code /api/v2/loyalty/super-admin/*}.  Drives the
 * M10 AdminLoyaltyCenter UI: program-level config, tier ladder edits,
 * perk catalog CRUD, and bulk binding actions (select-all →
 * Enable / Disable).
 *
 * <p>All mutations go through {@link LoyaltyAdminService} which handles
 * effective-date INSERTs (never UPDATE) + cache eviction.  This lets
 * any edit be undone by simply closing the new row and reactivating
 * the prior one — full reversibility with zero data loss.
 *
 * <p><b>Authorization:</b> defence-in-depth. Two layers enforce the
 * SUPER_ADMIN role:
 * <ol>
 *   <li>The api-gateway {@code JwtAuthenticationFilter} rejects any
 *       request to {@code /api/v*&#47;**&#47;super-admin/**} that does
 *       not carry a SUPER_ADMIN role claim.</li>
 *   <li>This service's {@code SecurityConfig} maps
 *       {@code /api/v2/loyalty/super-admin/**} to
 *       {@code hasRole("SUPER_ADMIN")} so any direct service-mesh
 *       traffic is also gated.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v2/loyalty/super-admin")
@RequiredArgsConstructor
public class LoyaltyV2SuperAdminController {

    private final LoyaltyAdminService adminService;
    private final LoyaltyConfigService configService;

    private final LoyaltyProgramRepository programRepository;
    private final LoyaltyTierDefinitionRepository tierRepository;
    private final LoyaltyPerkCatalogRepository perkRepository;
    private final LoyaltyBingeBindingRepository bindingRepository;


    // ── Program ──────────────────────────────────────────────────────────

    @GetMapping("/program")
    public ResponseEntity<ApiResponse<LoyaltyProgram>> getProgram() {
        return ResponseEntity.ok(ApiResponse.ok(configService.requireDefaultProgram()));
    }

    @PutMapping("/program")
    public ResponseEntity<ApiResponse<LoyaltyProgram>> updateProgram(
            @RequestBody LoyaltyProgram body) {
        LoyaltyProgram current = configService.requireDefaultProgram();
        body.setId(current.getId());                                        // force update, not insert
        return ResponseEntity.ok(ApiResponse.ok(programRepository.save(body)));
    }

    // ── Tier ladder ──────────────────────────────────────────────────────

    @GetMapping("/tiers")
    public ResponseEntity<ApiResponse<List<LoyaltyTierDefinition>>> listTiers() {
        LoyaltyProgram p = configService.requireDefaultProgram();
        return ResponseEntity.ok(ApiResponse.ok(configService.activeLadder(p.getId(), LocalDateTime.now())));
    }

    @PostMapping("/tiers")
    public ResponseEntity<ApiResponse<LoyaltyTierDefinition>> upsertTier(
            @RequestBody LoyaltyTierDefinition draft) {
        if (draft.getProgramId() == null) {
            draft.setProgramId(configService.requireDefaultProgram().getId());
        }
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertTier(draft, LocalDateTime.now())));
    }

    @DeleteMapping("/tiers/{tierId}")
    public ResponseEntity<ApiResponse<Void>> retireTier(@PathVariable Long tierId) {
        adminService.retireTier(tierId, LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Perk catalog ─────────────────────────────────────────────────────

    @GetMapping("/perks")
    public ResponseEntity<ApiResponse<List<LoyaltyPerkCatalog>>> listPerks() {
        LoyaltyProgram p = configService.requireDefaultProgram();
        return ResponseEntity.ok(ApiResponse.ok(configService.activePerks(p.getId(), LocalDateTime.now())));
    }

    @PostMapping("/perks")
    public ResponseEntity<ApiResponse<LoyaltyPerkCatalog>> savePerk(
            @RequestBody LoyaltyPerkCatalog draft) {
        if (draft.getProgramId() == null) {
            draft.setProgramId(configService.requireDefaultProgram().getId());
        }
        return ResponseEntity.ok(ApiResponse.ok(adminService.savePerk(draft)));
    }

    @PostMapping("/tier-perks")
    public ResponseEntity<ApiResponse<LoyaltyTierPerk>> assignPerkToTier(
            @RequestBody LoyaltyTierPerk mapping) {
        return ResponseEntity.ok(ApiResponse.ok(adminService.assignPerkToTier(mapping)));
    }

    @DeleteMapping("/tier-perks/{tierPerkId}")
    public ResponseEntity<ApiResponse<Void>> removePerkFromTier(@PathVariable Long tierPerkId) {
        adminService.removePerkFromTier(tierPerkId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Bulk binding actions (the AdminLoyaltyCenter star feature) ───────

    @GetMapping("/bindings")
    public ResponseEntity<ApiResponse<List<LoyaltyBingeBinding>>> listBindings() {
        return ResponseEntity.ok(ApiResponse.ok(bindingRepository.findAll()));
    }

    /**
     * Bulk-flip binding status.  Body is a list of binding IDs + the
     * desired status.  Legacy-frozen bindings are silently skipped
     * (they're immutable by design).
     */
    @PostMapping("/bindings/bulk")
    public ResponseEntity<ApiResponse<Integer>> bulkSetStatus(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody BulkBindingBody body) {
        int touched = adminService.bulkSetStatus(body.bindingIds(), body.status(), adminId);
        return ResponseEntity.ok(ApiResponse.ok(touched));
    }

    public record BulkBindingBody(List<Long> bindingIds, String status) { }

}
