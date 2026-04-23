package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyAdminService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.loyalty.v2.service.StatusMatchService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty v2 — ADMIN endpoints (per-binge scope).
 *
 * <p>Every endpoint requires {@code X-User-Role} of {@code ADMIN} or
 * {@code SUPER_ADMIN}.  Regular admins can only enable / disable
 * bindings for binges they manage; editing the <b>program-wide</b>
 * tier ladder / perk catalog is a super-admin capability (see the
 * sibling controller below).
 *
 * <p>Design note: this controller NEVER writes anything that v1
 * LoyaltyService reads, so it is safe to run in parallel with v1
 * during the shadow period.  All writes go through
 * {@link LoyaltyAdminService} which handles effective-dating +
 * cache eviction atomically.
 */
@RestController
@RequestMapping("/api/v2/loyalty/admin")
@RequiredArgsConstructor
public class LoyaltyV2AdminController {

    private final LoyaltyAdminService adminService;
    private final LoyaltyConfigService configService;
    private final StatusMatchService statusMatchService;

    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyBingeEarningRuleRepository earningRuleRepository;
    private final LoyaltyBingeRedemptionRuleRepository redemptionRuleRepository;
    private final LoyaltyStatusMatchRequestRepository statusMatchRepository;

    // ── Bindings ─────────────────────────────────────────────────────────

    @GetMapping("/bindings/{bingeId}")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> getBinding(
            @PathVariable Long bingeId) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        LoyaltyBingeBinding binding = configService.findActiveBinding(program.getId(), bingeId).orElse(null);
        return ResponseEntity.ok(ApiResponse.ok(binding));
    }

    @PostMapping("/bindings/{bingeId}/enable")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> enable(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long adminId) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.enableBingeForLoyalty(program.getId(), bingeId, null, adminId)));
    }

    @PostMapping("/bindings/{bindingId}/disable")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> disable(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.disableBingeForLoyalty(bindingId, adminId)));
    }

    // ── Per-binge earning / redemption rules ─────────────────────────────

    @GetMapping("/bindings/{bindingId}/earn-rules")
    public ResponseEntity<ApiResponse<List<LoyaltyBingeEarningRule>>> listEarnRules(
            @PathVariable Long bindingId) {
        return ResponseEntity.ok(ApiResponse.ok(
                earningRuleRepository.findActive(bindingId, LocalDateTime.now())));
    }

    @PostMapping("/bindings/{bindingId}/earn-rules")
    public ResponseEntity<ApiResponse<LoyaltyBingeEarningRule>> upsertEarnRule(
            @PathVariable Long bindingId,
            @RequestBody LoyaltyBingeEarningRule draft) {
        draft.setBindingId(bindingId);
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertEarningRule(draft, LocalDateTime.now())));
    }

    @GetMapping("/bindings/{bindingId}/redeem-rule")
    public ResponseEntity<ApiResponse<LoyaltyBingeRedemptionRule>> getRedeemRule(
            @PathVariable Long bindingId) {
        return ResponseEntity.ok(ApiResponse.ok(
                redemptionRuleRepository.findActive(bindingId, LocalDateTime.now()).orElse(null)));
    }

    @PostMapping("/bindings/{bindingId}/redeem-rule")
    public ResponseEntity<ApiResponse<LoyaltyBingeRedemptionRule>> upsertRedeemRule(
            @PathVariable Long bindingId,
            @RequestBody LoyaltyBingeRedemptionRule draft) {
        draft.setBindingId(bindingId);
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertRedemptionRule(draft, LocalDateTime.now())));
    }

    // ── Perk overrides ───────────────────────────────────────────────────

    @PostMapping("/bindings/{bindingId}/perks")
    public ResponseEntity<ApiResponse<LoyaltyBingePerkOverride>> upsertPerkOverride(
            @PathVariable Long bindingId,
            @RequestBody LoyaltyBingePerkOverride draft) {
        draft.setBindingId(bindingId);
        return ResponseEntity.ok(ApiResponse.ok(adminService.upsertPerkOverride(draft)));
    }

    // ── Status-match review queue ────────────────────────────────────────

    @GetMapping("/status-match/pending")
    public ResponseEntity<ApiResponse<Page<LoyaltyStatusMatchRequest>>> listPending(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(statusMatchRepository
                .findByStatusOrderByCreatedAtAsc("PENDING",
                        PageRequest.of(page, Math.min(size, 100)))));
    }

    @PostMapping("/status-match/{requestId}/approve")
    public ResponseEntity<ApiResponse<LoyaltyStatusMatchRequest>> approve(
            @PathVariable Long requestId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody StatusMatchApproveBody body) {
        return ResponseEntity.ok(ApiResponse.ok(statusMatchService.approve(
                requestId, adminId,
                body.notes() == null ? "" : body.notes(),
                body.challengeDays() == null ? 90 : body.challengeDays())));
    }

    @PostMapping("/status-match/{requestId}/reject")
    public ResponseEntity<ApiResponse<LoyaltyStatusMatchRequest>> reject(
            @PathVariable Long requestId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestBody StatusMatchApproveBody body) {
        return ResponseEntity.ok(ApiResponse.ok(
                statusMatchService.reject(requestId, adminId, body.notes() == null ? "" : body.notes())));
    }

    /** Inline request body — approving or rejecting a status-match. */
    public record StatusMatchApproveBody(String notes, Integer challengeDays) { }

    // Keep the import-usage of Optional from being pruned during
    // refactors — helper kept intentionally.
    @SuppressWarnings("unused")
    private static <T> T firstOrNull(Optional<T> opt) { return opt.orElse(null); }
}
