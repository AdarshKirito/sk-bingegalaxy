package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService;
import com.skbingegalaxy.booking.loyalty.v2.service.StatusMatchService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loyalty v2 — CUSTOMER-facing endpoints.
 *
 * <p>Served at {@code /api/v2/loyalty/me/*}.  Auth relies on the upstream
 * API-gateway injecting {@code X-User-Id} and {@code X-User-Role} headers.
 * Every endpoint operates on "the member for {@code X-User-Id}" exclusively.
 *
 * <p>Response shape uses canonical v2 field names — no legacy aliases.
 * The frontend {@code loyaltyV2.js} maps these directly without a
 * compatibility shim.
 */
@RestController
@RequestMapping("/api/v2/loyalty/me")
@RequiredArgsConstructor
public class LoyaltyV2CustomerController {

    private final EnrollmentService enrollmentService;
    private final LoyaltyMemberService loyaltyMemberService;
    private final RedeemEngine redeemEngine;
    private final StatusMatchService statusMatchService;

    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyLedgerEntryRepository ledgerRepository;
    private final LoyaltyStatusMatchRequestRepository statusMatchRepository;

    // ── Membership snapshot ──────────────────────────────────────────────

    /**
     * Returns the caller's membership snapshot.
     *
     * <p>Just-in-time enrollment (Marriott Bonvoy / Hilton Honors pattern):
     * a signed-in CUSTOMER with no membership yet is silently enrolled at
     * BRONZE with the welcome bonus.  Staff roles (ADMIN, SUPER_ADMIN) are
     * never auto-enrolled — the loyalty program is for customers only.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyMembership(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            if (!isCustomerRole(role)) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of("enrolled", false)));
            }
            m = enrollmentService.enrollExplicit(customerId, LoyaltyV2Constants.ENROLL_AUTO_DASHBOARD);
        }

        LoyaltyMemberService.MemberProfile profile =
                loyaltyMemberService.getMemberProfile(customerId);

        Map<String, Object> out = new HashMap<>();
        out.put("enrolled",                  true);
        out.put("membershipId",              profile.membershipId());
        out.put("memberNumber",              profile.memberNumber());
        out.put("tierCode",                  profile.tierCode());
        out.put("tierEffectiveFrom",         profile.tierEffectiveFrom());
        out.put("tierEffectiveUntil",        profile.tierEffectiveUntil());   // null = permanent
        out.put("qualifyingCreditsWindow",   profile.qualifyingCreditsWindow());
        out.put("lifetimeCredits",           profile.lifetimeCredits());
        out.put("pointsBalance",             profile.pointsBalance());
        out.put("pointsEarnedLifetime",      profile.pointsEarnedLifetime());
        out.put("pointsRedeemedLifetime",    profile.pointsRedeemedLifetime());
        out.put("pointsToNextTier",          profile.pointsToNextTier());
        out.put("nextTierCode",              profile.nextTierCode());
        out.put("redemptionRate",            profile.redemptionRate());
        out.put("enrolledAt",                profile.enrolledAt());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Loyalty program is customer-only.  A missing/blank role is treated as
     * CUSTOMER for backward compatibility with gateway versions that omit the
     * header on customer requests.
     */
    private boolean isCustomerRole(String role) {
        if (role == null || role.isBlank()) return true;
        String r = role.trim().toUpperCase();
        return r.equals("CUSTOMER") || r.equals("USER")
                || r.equals("ROLE_CUSTOMER") || r.equals("ROLE_USER");
    }

    // ── Ledger (paginated) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    @GetMapping("/ledger")
    public ResponseEntity<ApiResponse<Page<LoyaltyLedgerEntry>>> getMyLedger(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId)
                .orElseThrow(() -> new IllegalStateException("Member not enrolled"));
        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet missing for membership " + m.getId()));

        Page<LoyaltyLedgerEntry> ledger = ledgerRepository
                .findByWalletIdOrderByCreatedAtDesc(
                        w.getId(), PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(ApiResponse.ok(ledger));
    }

    // ── Redeem quote (preview only — no state change) ────────────────────

    @Transactional(readOnly = true)
    @GetMapping("/redeem-quote")
    public ResponseEntity<ApiResponse<RedeemEngine.RedeemQuote>> quoteRedeem(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestParam Long bingeId,
            @RequestParam BigDecimal bookingAmount,
            @RequestParam long points) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId)
                .orElseThrow(() -> new IllegalStateException("Member not enrolled"));

        RedeemEngine.RedeemQuote quote = redeemEngine.quote(new RedeemEngine.QuoteRequest(
                m.getId(), bingeId, points, bookingAmount, java.time.LocalDateTime.now()));
        return ResponseEntity.ok(ApiResponse.ok(quote));
    }

    // ── Status match ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @GetMapping("/status-match")
    public ResponseEntity<ApiResponse<List<LoyaltyStatusMatchRequest>>> listMyStatusMatches(
            @RequestHeader("X-User-Id") Long customerId) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId)
                .orElseThrow(() -> new IllegalStateException("Member not enrolled"));
        return ResponseEntity.ok(ApiResponse.ok(
                statusMatchRepository.findByMembershipIdOrderByCreatedAtDesc(m.getId())));
    }

    @PostMapping("/status-match")
    public ResponseEntity<ApiResponse<LoyaltyStatusMatchRequest>> submitStatusMatch(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestBody StatusMatchSubmitBody body) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId)
                .orElseThrow(() -> new IllegalStateException("Member not enrolled"));
        LoyaltyStatusMatchRequest req = statusMatchService.submit(
                m.getId(),
                body.competitorProgramName(),
                body.competitorTierName(),
                body.requestedTierCode(),
                body.proofUrl(),
                body.proofPayloadJson());
        return ResponseEntity.ok(ApiResponse.ok(req));
    }

    public record StatusMatchSubmitBody(
            String competitorProgramName,
            String competitorTierName,
            String requestedTierCode,
            String proofUrl,
            String proofPayloadJson
    ) {}
}
