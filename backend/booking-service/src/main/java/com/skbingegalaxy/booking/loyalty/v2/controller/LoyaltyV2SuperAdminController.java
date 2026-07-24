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
    private final LoyaltyCountryEarnConfigRepository countryConfigRepository;

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

    // ── Country earn/redeem economics ────────────────────────────────────

    /**
     * Per-country loyalty economics (earn rate + redemption value in LOCAL
     * currency). Drives what NEW binges are auto-seeded with; existing binges
     * keep their per-binge rules (edit those individually to retro-change).
     */
    @GetMapping("/country-configs")
    public ResponseEntity<ApiResponse<List<LoyaltyCountryEarnConfig>>> listCountryConfigs() {
        return ResponseEntity.ok(ApiResponse.ok(
                countryConfigRepository.findAll(org.springframework.data.domain.Sort.by("countryIso2"))));
    }

    @PostMapping("/country-configs")
    public ResponseEntity<ApiResponse<LoyaltyCountryEarnConfig>> upsertCountryConfig(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody LoyaltyCountryEarnConfig body) {
        if (body.getCountryIso2() == null || !body.getCountryIso2().trim().matches("(?i)[A-Z]{2}")) {
            throw new BusinessException("countryIso2 must be a 2-letter ISO code");
        }
        if (body.getCurrencyCode() == null || body.getCurrencyCode().isBlank()) {
            throw new BusinessException("currencyCode is required");
        }
        if (body.getPointsNumerator() <= 0) throw new BusinessException("pointsNumerator must be > 0");
        if (body.getAmountDenominator() == null || body.getAmountDenominator().signum() <= 0) {
            throw new BusinessException("amountDenominator must be > 0");
        }
        if (body.getPointsPerCurrencyUnit() <= 0) {
            throw new BusinessException("pointsPerCurrencyUnit must be > 0");
        }
        if (body.getMaxRedemptionPercent() == null
                || body.getMaxRedemptionPercent().signum() <= 0
                || body.getMaxRedemptionPercent().compareTo(new java.math.BigDecimal("100")) > 0) {
            throw new BusinessException("maxRedemptionPercent must be in (0, 100]");
        }
        body.setCountryIso2(body.getCountryIso2().trim().toUpperCase());
        body.setCurrencyCode(body.getCurrencyCode().trim().toUpperCase());
        body.setUpdatedByAdminId(adminId);
        return ResponseEntity.ok(ApiResponse.ok("Country config saved",
                countryConfigRepository.save(body)));
    }

    @DeleteMapping("/country-configs/{countryIso2}")
    public ResponseEntity<ApiResponse<Void>> deleteCountryConfig(@PathVariable String countryIso2) {
        countryConfigRepository.deleteById(countryIso2.trim().toUpperCase());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Per-binge goodwill permission ────────────────────────────────────

    /**
     * Grant/revoke a binge's ability to sanction goodwill points, with a
     * monthly budget. Body: {"goodwillEnabled": true, "goodwillMonthlyCapPoints": 50000}.
     */
    @PostMapping("/bindings/{bindingId}/goodwill-settings")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> setGoodwillSettings(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody Map<String, Object> body) {
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new BusinessException("Binding not found: " + bindingId));
        if (body.get("goodwillEnabled") instanceof Boolean enabled) {
            binding.setGoodwillEnabled(enabled);
        }
        if (body.get("goodwillMonthlyCapPoints") instanceof Number cap) {
            if (cap.longValue() < 0) throw new BusinessException("Monthly cap must be >= 0");
            binding.setGoodwillMonthlyCapPoints(cap.longValue());
        }
        LoyaltyBingeBinding saved = bindingRepository.save(binding);
        return ResponseEntity.ok(ApiResponse.ok("Goodwill settings updated", saved));
    }

    // ── Per-binge loyalty configuration lock ─────────────────────────────

    /**
     * Lock/unlock a binge's loyalty configuration to super-admin control.
     * Body: {"locked": true}. When locked, the binge's own admins can no longer
     * enable/disable the binding or edit its earn/redeem/perk rules — the super
     * admin owns the venue's loyalty economics (typically set at approval).
     */
    @PostMapping("/bindings/{bindingId}/config-lock")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> setConfigLock(
            @PathVariable Long bindingId,
            @RequestBody Map<String, Object> body) {
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new BusinessException("Binding not found: " + bindingId));
        if (!(body.get("locked") instanceof Boolean locked)) {
            throw new BusinessException("Request body must include boolean 'locked'");
        }
        binding.setAdminConfigLocked(locked);
        LoyaltyBingeBinding saved = bindingRepository.save(binding);
        return ResponseEntity.ok(ApiResponse.ok(
                locked ? "Loyalty config locked to super-admin control"
                       : "Loyalty config unlocked for binge admins", saved));
    }

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
