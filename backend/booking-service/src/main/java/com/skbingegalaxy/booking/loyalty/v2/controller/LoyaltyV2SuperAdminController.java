package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyAdminService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Loyalty v2 — SUPER-ADMIN endpoints (program-wide scope).
 *
 * <p>Served at {@code /api/v2/loyalty/super-admin/*}.  Drives the
 * AdminLoyaltyCenter UI: program-level config, tier ladder edits, perk
 * catalog CRUD, bulk binding actions, and per-customer wallet operations.
 *
 * <p><b>Authorization:</b> three-layer defence-in-depth:
 * <ol>
 *   <li>API-gateway {@code JwtAuthenticationFilter} rejects requests to
 *       /api/v2/loyalty/super-admin/** without SUPER_ADMIN role claim.</li>
 *   <li>This service's {@code SecurityConfig} maps
 *       /api/v2/loyalty/super-admin/** to {@code hasRole("SUPER_ADMIN")}.</li>
 *   <li>{@code @PreAuthorize("hasRole('SUPER_ADMIN')")} at the class level
 *       enforces the role even for direct service-mesh or internal callers
 *       that bypass the URL-matcher (e.g. forward-dispatched requests).</li>
 * </ol>
 *
 * <p>All config mutations go through {@link LoyaltyAdminService} which
 * enforces effective-dated INSERTs (never UPDATE) and cache eviction,
 * giving full reversibility with zero data loss.
 */
@RestController
@RequestMapping("/api/v2/loyalty/super-admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class LoyaltyV2SuperAdminController {

    private final LoyaltyAdminService adminService;
    private final LoyaltyConfigService configService;
    private final LoyaltyMemberService loyaltyMemberService;

    private final LoyaltyProgramRepository programRepository;
    private final LoyaltyTierDefinitionRepository tierRepository;
    private final LoyaltyPerkCatalogRepository perkRepository;
    private final LoyaltyTierPerkRepository tierPerkRepository;
    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyLedgerEntryRepository ledgerRepository;

    // ── Program ──────────────────────────────────────────────────────────

    @GetMapping("/program")
    public ResponseEntity<ApiResponse<LoyaltyProgram>> getProgram() {
        return ResponseEntity.ok(ApiResponse.ok(configService.requireDefaultProgram()));
    }

    @PutMapping("/program")
    @CacheEvict(value = "loyaltyV2.programs", allEntries = true)
    public ResponseEntity<ApiResponse<LoyaltyProgram>> updateProgram(
            @RequestBody LoyaltyProgram body) {
        LoyaltyProgram current = configService.requireDefaultProgram();
        body.setId(current.getId());   // force UPDATE, not INSERT
        return ResponseEntity.ok(ApiResponse.ok(programRepository.save(body)));
    }

    // ── Tier ladder ──────────────────────────────────────────────────────

    @GetMapping("/tiers")
    public ResponseEntity<ApiResponse<List<LoyaltyTierDefinition>>> listTiers() {
        LoyaltyProgram p = configService.requireDefaultProgram();
        return ResponseEntity.ok(ApiResponse.ok(
                configService.activeLadder(p.getId(), LocalDateTime.now(ZoneOffset.UTC))));
    }

    @PostMapping("/tiers")
    public ResponseEntity<ApiResponse<LoyaltyTierDefinition>> upsertTier(
            @RequestBody LoyaltyTierDefinition draft) {
        if (draft.getProgramId() == null) {
            draft.setProgramId(configService.requireDefaultProgram().getId());
        }
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertTier(draft, LocalDateTime.now(ZoneOffset.UTC))));
    }

    @DeleteMapping("/tiers/{tierId}")
    public ResponseEntity<ApiResponse<Void>> retireTier(@PathVariable Long tierId) {
        adminService.retireTier(tierId, LocalDateTime.now(ZoneOffset.UTC));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Perk catalog ─────────────────────────────────────────────────────

    @GetMapping("/perks")
    public ResponseEntity<ApiResponse<List<LoyaltyPerkCatalog>>> listPerks() {
        LoyaltyProgram p = configService.requireDefaultProgram();
        return ResponseEntity.ok(ApiResponse.ok(
                configService.activePerks(p.getId(), LocalDateTime.now(ZoneOffset.UTC))));
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
    public ResponseEntity<ApiResponse<Void>> removePerkFromTier(
            @PathVariable Long tierPerkId) {
        adminService.removePerkFromTier(tierPerkId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Bulk binding actions ─────────────────────────────────────────────

    @GetMapping("/bindings")
    public ResponseEntity<ApiResponse<List<LoyaltyBingeBinding>>> listBindings() {
        return ResponseEntity.ok(ApiResponse.ok(bindingRepository.findAll()));
    }

    /**
     * Bulk-flip binding status.  Legacy-frozen bindings are unfrozen when
     * enabling (they move from ENABLED_LEGACY to ENABLED).
     */
    @PostMapping("/bindings/bulk")
    public ResponseEntity<ApiResponse<Integer>> bulkSetStatus(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody BulkBindingBody body) {
        int touched = adminService.bulkSetStatus(body.bindingIds(), body.status(), adminId);
        return ResponseEntity.ok(ApiResponse.ok(touched));
    }

    public record BulkBindingBody(List<Long> bindingIds, String status) {}

    // ── Per-customer wallet operations ───────────────────────────────────

    /**
     * Return a full membership snapshot for any customer.
     * Used by the support dashboard to inspect a member's wallet.
     */
    @GetMapping("/customers/{customerId}")
    public ResponseEntity<ApiResponse<LoyaltyMemberService.MemberProfile>> getCustomerAccount(
            @PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(
                loyaltyMemberService.getMemberProfile(customerId)));
    }

    /**
     * Credit or debit a customer's loyalty balance as a manual admin action.
     * Body: {"points": 500, "description": "Goodwill credit — support ticket #1234"}.
     * Positive points = credit; negative = debit.
     */
    @PostMapping("/customers/{customerId}/adjust")
    public ResponseEntity<ApiResponse<LoyaltyMemberService.MemberProfile>> adjustCustomerPoints(
            @PathVariable Long customerId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Role") String role) {

        if (body == null || !(body.get("points") instanceof Number)) {
            throw new BusinessException("Request body must include numeric 'points'");
        }
        long points = ((Number) body.get("points")).longValue();
        String description = body.get("description") instanceof String s ? s : null;

        return ResponseEntity.ok(ApiResponse.ok(
                "Points adjusted",
                loyaltyMemberService.adjustPoints(customerId, points, description, role)));
    }

    // ── Per-customer ledger ──────────────────────────────────────────────────

    /** Paginated ledger for any customer — for support/admin review. Read-only. */
    @Transactional(readOnly = true)
    @GetMapping("/customers/{customerId}/ledger")
    public ResponseEntity<ApiResponse<Page<LoyaltyLedgerEntry>>> getCustomerLedger(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        LoyaltyMemberService.MemberProfile profile =
                loyaltyMemberService.getMemberProfile(customerId);
        if (profile.membershipId() == null) {
            return ResponseEntity.ok(ApiResponse.ok(Page.empty()));
        }
        LoyaltyPointsWallet wallet =
                walletRepository.findByMembershipId(profile.membershipId()).orElse(null);
        if (wallet == null) {
            return ResponseEntity.ok(ApiResponse.ok(Page.empty()));
        }
        return ResponseEntity.ok(ApiResponse.ok(
                ledgerRepository.findByWalletIdOrderByCreatedAtDesc(
                        wallet.getId(), PageRequest.of(page, Math.min(size, 100)))));
    }

    // ── Tier-perk assignments ────────────────────────────────────────────────

    /** List tier-perk mappings — all tiers when tierId is omitted, or filtered. */
    @GetMapping("/tier-perks")
    public ResponseEntity<ApiResponse<List<LoyaltyTierPerk>>> listTierPerks(
            @RequestParam(required = false) Long tierId) {
        if (tierId != null) {
            return ResponseEntity.ok(ApiResponse.ok(
                    tierPerkRepository.findByTierDefinitionIdOrderBySortOrderAsc(tierId)));
        }
        return ResponseEntity.ok(ApiResponse.ok(tierPerkRepository.findAll()));
    }
}
