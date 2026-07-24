package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyAdminService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.loyalty.v2.service.StatusMatchService;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class LoyaltyV2AdminController {

    private final LoyaltyAdminService adminService;
    private final LoyaltyConfigService configService;
    private final StatusMatchService statusMatchService;
    private final AdminBingeScopeService adminBingeScopeService;
    private final com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService enrollmentService;
    private final com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService loyaltyMemberService;

    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyBingeEarningRuleRepository earningRuleRepository;
    private final LoyaltyBingeRedemptionRuleRepository redemptionRuleRepository;
    private final LoyaltyStatusMatchRequestRepository statusMatchRepository;

    // ── Enrollment (desk registration) ───────────────────────────────────

    /** Body for {@link #enrollCustomer}. */
    public record EnrollCustomerRequest(@jakarta.validation.constraints.NotNull Long customerId) {}

    /**
     * Enroll a customer the admin just registered at the desk.
     *
     * <p>Gives desk-registered customers the exact same benefits a self-signup
     * gets: Bronze membership, a member number, and the program's welcome
     * bonus points on FIRST enrollment. Idempotent — re-posting for an already
     * enrolled customer returns the existing membership with
     * {@code alreadyEnrolled=true} and never double-awards the bonus.
     *
     * <p>Best-effort by design on the caller side: if the admin UI never makes
     * this call (offline, crash), the customer is still enrolled lazily with
     * the same welcome bonus on their first booking
     * ({@code EnrollmentService.ensureEnrolledForBooking}).
     */
    @PostMapping("/enrollments")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> enrollCustomer(
            @jakarta.validation.Valid @RequestBody EnrollCustomerRequest request) {
        boolean alreadyEnrolled = enrollmentService.findForCustomer(request.customerId()).isPresent();
        LoyaltyMembership membership = alreadyEnrolled
                ? enrollmentService.findForCustomer(request.customerId()).get()
                : enrollmentService.enrollExplicit(request.customerId(),
                    com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants.ENROLL_ADMIN_REGISTRATION);
        LoyaltyProgram program = configService.requireDefaultProgram();
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("membershipId", membership.getId());
        body.put("memberNumber", membership.getMemberNumber());
        body.put("tierCode", membership.getCurrentTierCode());
        body.put("alreadyEnrolled", alreadyEnrolled);
        body.put("welcomeBonusPoints", alreadyEnrolled ? 0 : program.getWelcomeBonusPoints());
        return ResponseEntity.status(alreadyEnrolled ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.ok(alreadyEnrolled
                        ? "Customer is already a member"
                        : "Customer enrolled in " + program.getDisplayName(), body));
    }

    // ── Bindings ─────────────────────────────────────────────────────────

    @GetMapping("/bindings/{bingeId}")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> getBinding(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // IDOR guard: verify the calling admin owns this binge (SUPER_ADMIN bypasses)
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "reading loyalty binding");
        LoyaltyProgram program = configService.requireDefaultProgram();
        return bindingRepository.findByProgramIdAndBingeId(program.getId(), bingeId)
                .<ResponseEntity<ApiResponse<LoyaltyBingeBinding>>>map(b -> ResponseEntity.ok(ApiResponse.ok(b)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("No loyalty binding found for binge " + bingeId)));
    }

    @PostMapping("/bindings/{bingeId}/enable")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> enable(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // IDOR guard: verify the calling admin owns this binge
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "enabling loyalty for binge");
        LoyaltyProgram program = configService.requireDefaultProgram();
        // Governance gate: if a binding already exists and the super-admin locked
        // its config, a regular admin can't flip it back on.
        bindingRepository.findByProgramIdAndBingeId(program.getId(), bingeId)
                .ifPresent(b -> assertMayConfigure(b, role));
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.enableBingeForLoyalty(program.getId(), bingeId, null, adminId)));
    }

    @PostMapping("/bindings/{bindingId}/disable")
    public ResponseEntity<ApiResponse<LoyaltyBingeBinding>> disable(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // IDOR guard: load the binding to get its bingeId, then verify ownership
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "disabling loyalty binding");
        assertMayConfigure(binding, role);
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.disableBingeForLoyalty(bindingId, adminId)));
    }

    // ── Goodwill (service-recovery) points ───────────────────────────────

    /** Remaining goodwill budget for this binge (drives the admin UI). */
    @GetMapping("/goodwill/{bingeId}/budget")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService.GoodwillBudget>> goodwillBudget(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "reading goodwill budget");
        return ResponseEntity.ok(ApiResponse.ok(loyaltyMemberService.goodwillBudget(bingeId)));
    }

    /** Body for {@link #grantGoodwill}. */
    public record GoodwillGrantRequest(
            @jakarta.validation.constraints.NotNull Long customerId,
            @jakarta.validation.constraints.Positive long points,
            @jakarta.validation.constraints.NotBlank String reason,
            String bookingRef) {}

    /**
     * Credit goodwill points to a customer disappointed by an event at this
     * binge. Requires the super-admin-granted goodwill permission on the
     * binge's binding and is capped by its monthly budget.
     */
    @PostMapping("/goodwill/{bingeId}")
    public ResponseEntity<ApiResponse<com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService.MemberProfile>> grantGoodwill(
            @PathVariable Long bingeId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @jakarta.validation.Valid @RequestBody GoodwillGrantRequest request) {
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "granting goodwill points");
        return ResponseEntity.ok(ApiResponse.ok("Goodwill points credited",
                loyaltyMemberService.grantGoodwill(request.customerId(), bingeId,
                        request.points(), request.reason(), request.bookingRef(), adminId)));
    }

    // ── Per-binge earning / redemption rules ─────────────────────────────

    @GetMapping("/bindings/{bindingId}/earn-rules")
    public ResponseEntity<ApiResponse<List<LoyaltyBingeEarningRule>>> listEarnRules(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // IDOR guard: verify admin owns the binge this binding belongs to
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "listing earn rules");
        return ResponseEntity.ok(ApiResponse.ok(
                earningRuleRepository.findActive(bindingId, LocalDateTime.now(ZoneOffset.UTC))));
    }

    @PostMapping("/bindings/{bindingId}/earn-rules")
    public ResponseEntity<ApiResponse<LoyaltyBingeEarningRule>> upsertEarnRule(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody LoyaltyBingeEarningRule draft) {
        // IDOR guard: verify admin owns the binge before modifying its earn rules
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "upserting earn rule");
        assertMayConfigure(binding, role);
        draft.setBindingId(bindingId);
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertEarningRule(draft, LocalDateTime.now(ZoneOffset.UTC))));
    }

    @GetMapping("/bindings/{bindingId}/redeem-rule")
    public ResponseEntity<ApiResponse<LoyaltyBingeRedemptionRule>> getRedeemRule(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // IDOR guard
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "reading redeem rule");
        return redemptionRuleRepository.findActive(bindingId, LocalDateTime.now(ZoneOffset.UTC))
                .<ResponseEntity<ApiResponse<LoyaltyBingeRedemptionRule>>>map(r -> ResponseEntity.ok(ApiResponse.ok(r)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("No active redemption rule for binding " + bindingId)));
    }

    @PostMapping("/bindings/{bindingId}/redeem-rule")
    public ResponseEntity<ApiResponse<LoyaltyBingeRedemptionRule>> upsertRedeemRule(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody LoyaltyBingeRedemptionRule draft) {
        // IDOR guard
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "upserting redeem rule");
        assertMayConfigure(binding, role);
        draft.setBindingId(bindingId);
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.upsertRedemptionRule(draft, LocalDateTime.now(ZoneOffset.UTC))));
    }

    /**
     * Reset this binge's redemption to the LIVE country default: retire any
     * per-binge override so the binge inherits its venue-country economics from
     * the super-admin Countries tab (and tracks future edits there). Read the
     * resolved terms with {@link #effectiveRedeem}.
     */
    @PostMapping("/bindings/{bindingId}/redeem-rule/reset")
    public ResponseEntity<ApiResponse<Void>> resetRedeemRule(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "resetting redeem rule");
        assertMayConfigure(binding, role);
        adminService.retireRedemptionRule(bindingId, LocalDateTime.now(ZoneOffset.UTC));
        return ResponseEntity.ok(ApiResponse.ok(
                "Redemption reset — this venue now inherits its country's live point value", null));
    }

    /**
     * The redemption economics that ACTUALLY apply to this binge right now and
     * where they come from (per-binge override, the venue's country config, or
     * the program default). Drives the "currently inheriting …" banner in the
     * binge loyalty panel.
     */
    @GetMapping("/bindings/{bindingId}/effective-redeem")
    public ResponseEntity<ApiResponse<LoyaltyConfigService.EffectiveRedemptionTerms>> effectiveRedeem(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "reading effective redeem terms");
        return ResponseEntity.ok(ApiResponse.ok(configService.resolveEffectiveRedemption(
                bindingId, binding.getBingeId(), LocalDateTime.now(ZoneOffset.UTC))));
    }

    // ── Perk overrides ───────────────────────────────────────────────────

    @PostMapping("/bindings/{bindingId}/perks")
    public ResponseEntity<ApiResponse<LoyaltyBingePerkOverride>> upsertPerkOverride(
            @PathVariable Long bindingId,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody LoyaltyBingePerkOverride draft) {
        // IDOR guard
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
            .orElseThrow(() -> new ResourceNotFoundException("LoyaltyBingeBinding", "id", bindingId));
        adminBingeScopeService.requireBingeOwnership(binding.getBingeId(), adminId, role, "upserting perk override");
        assertMayConfigure(binding, role);
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

    // ── Config-lock enforcement ──────────────────────────────────────────

    private static boolean isSuperAdmin(String role) {
        return role != null && "SUPER_ADMIN".equalsIgnoreCase(role.trim());
    }

    /**
     * Fail-closed governance gate: a binge admin may not change loyalty
     * configuration on a binding the super-admin has locked. Super-admins always
     * pass. Called on every admin WRITE to loyalty config (enable/disable/rules).
     */
    private void assertMayConfigure(LoyaltyBingeBinding binding, String role) {
        if (binding != null && binding.isAdminConfigLocked() && !isSuperAdmin(role)) {
            throw new BusinessException(
                    "Loyalty configuration for this venue is managed by the super admin. "
                            + "Ask a super admin to change it or to unlock self-service.",
                    HttpStatus.FORBIDDEN);
        }
    }

    // Keep the import-usage of Optional from being pruned during
    // refactors — helper kept intentionally.
    @SuppressWarnings("unused")
    private static <T> T firstOrNull(Optional<T> opt) { return opt.orElse(null); }
}
