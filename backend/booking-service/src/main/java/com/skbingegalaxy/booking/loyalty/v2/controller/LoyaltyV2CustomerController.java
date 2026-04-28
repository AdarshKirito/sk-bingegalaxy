package com.skbingegalaxy.booking.loyalty.v2.controller;

import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.loyalty.v2.service.StatusMatchService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loyalty v2 — CUSTOMER-facing endpoints.
 *
 * <p>Served at {@code /api/v2/loyalty/me/*}.  Keeps a generous gap
 * from the v1 {@code /api/v1/bookings/loyalty} so both can run in
 * parallel during the Phase-1 shadow period (M11) — the frontend
 * explicitly chooses which version to call via a feature flag.
 *
 * <p><b>Auth:</b> relies on the upstream API-gateway setting
 * {@code X-User-Id} and {@code X-User-Role} headers, the same
 * convention every other controller in this service uses.  No
 * additional authorization logic is needed — every endpoint acts on
 * "the member for {@code X-User-Id}" and only that member.
 */
@RestController
@RequestMapping("/api/v2/loyalty/me")
@RequiredArgsConstructor
public class LoyaltyV2CustomerController {

    private final EnrollmentService enrollmentService;
    private final LoyaltyConfigService configService;
    private final RedeemEngine redeemEngine;
    private final StatusMatchService statusMatchService;

    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyLedgerEntryRepository ledgerRepository;
    private final LoyaltyStatusMatchRequestRepository statusMatchRepository;

    // ── Membership snapshot ──────────────────────────────────────────────

    /**
     * Returns the caller's membership snapshot.
     *
     * <p><b>Just-in-time enrollment</b> (industry standard — Marriott Bonvoy,
     * Hilton Honors, Sephora Beauty Insider, Starbucks Rewards): if a CUSTOMER
     * with no membership yet hits this endpoint we silently enroll them at
     * BRONZE and award the welcome bonus. This guarantees every signed-in
     * customer immediately sees the dashboard rather than the static "Join"
     * panel — without requiring a registration-time hook (which would miss
     * OAuth/Google/social logins).
     *
     * <p>Admin / super-admin / staff roles never auto-enroll — the loyalty
     * program is for customers only.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyMembership(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElse(null);
        if (m == null) {
            if (!isCustomerRole(role)) {
                // Staff roles (ADMIN, SUPER_ADMIN, etc.) are not loyalty members.
                return ResponseEntity.ok(ApiResponse.ok(Map.of("enrolled", false)));
            }
            m = enrollmentService.enrollExplicit(
                    customerId,
                    com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants.ENROLL_AUTO_DASHBOARD);
        }

        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElse(null);
        Map<String, Object> out = new HashMap<>();
        out.put("enrolled", true);
        out.put("membershipId", m.getId());
        out.put("memberNumber", m.getMemberNumber());
        out.put("tierCode", m.getCurrentTierCode());
        out.put("tierEffectiveFrom", m.getTierEffectiveFrom());
        out.put("tierEffectiveUntil", m.getTierEffectiveUntil());          // null = permanent
        out.put("qualifyingCreditsWindow", m.getQualifyingCreditsWindow());
        out.put("lifetimeCredits", m.getLifetimeCredits());
        out.put("pointsBalance", w == null ? 0L : w.getCurrentBalance());
        out.put("pointsEarnedLifetime", w == null ? 0L : w.getLifetimeEarned());
        out.put("pointsRedeemedLifetime", w == null ? 0L : w.getLifetimeRedeemed());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Loyalty program is customer-only. Treat a missing/blank role as customer
     * for backward compatibility with older gateway versions that may not set
     * the header on customer requests.
     */
    private boolean isCustomerRole(String role) {
        if (role == null || role.isBlank()) return true;
        String r = role.trim().toUpperCase();
        return r.equals("CUSTOMER") || r.equals("USER") || r.equals("ROLE_CUSTOMER") || r.equals("ROLE_USER");
    }

    // ── Ledger (paginated) ──────────────────────────────────────────────

    @GetMapping("/ledger")
    public ResponseEntity<ApiResponse<Page<LoyaltyLedgerEntry>>> getMyLedger(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElseThrow(
                () -> new IllegalStateException("Member not enrolled"));
        LoyaltyPointsWallet w = walletRepository.findByMembershipId(m.getId()).orElseThrow(
                () -> new IllegalStateException("Wallet missing for membership " + m.getId()));

        Page<LoyaltyLedgerEntry> ledger = ledgerRepository
                .findByWalletIdOrderByCreatedAtDesc(w.getId(), PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(ApiResponse.ok(ledger));
    }

    // ── Redeem quote (preview only, no state change) ─────────────────────

    @GetMapping("/redeem-quote")
    public ResponseEntity<ApiResponse<RedeemEngine.RedeemQuote>> quoteRedeem(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestParam Long bingeId,
            @RequestParam BigDecimal bookingAmount,
            @RequestParam long points) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElseThrow(
                () -> new IllegalStateException("Member not enrolled"));

        RedeemEngine.RedeemQuote quote = redeemEngine.quote(new RedeemEngine.QuoteRequest(
                m.getId(), bingeId, points, bookingAmount, java.time.LocalDateTime.now()));
        return ResponseEntity.ok(ApiResponse.ok(quote));
    }

    // ── Status match ─────────────────────────────────────────────────────

    @GetMapping("/status-match")
    public ResponseEntity<ApiResponse<List<LoyaltyStatusMatchRequest>>> listMyStatusMatches(
            @RequestHeader("X-User-Id") Long customerId) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElseThrow(
                () -> new IllegalStateException("Member not enrolled"));
        return ResponseEntity.ok(ApiResponse.ok(
                statusMatchRepository.findByMembershipIdOrderByCreatedAtDesc(m.getId())));
    }

    @PostMapping("/status-match")
    public ResponseEntity<ApiResponse<LoyaltyStatusMatchRequest>> submitStatusMatch(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestBody StatusMatchSubmitBody body) {

        LoyaltyMembership m = enrollmentService.findForCustomer(customerId).orElseThrow(
                () -> new IllegalStateException("Member not enrolled"));
        LoyaltyStatusMatchRequest req = statusMatchService.submit(
                m.getId(),
                body.competitorProgramName(),
                body.competitorTierName(),
                body.requestedTierCode(),
                body.proofUrl(),
                body.proofPayloadJson());
        return ResponseEntity.ok(ApiResponse.ok(req));
    }

    /** Request body for status-match submission (inline to avoid a dedicated DTO). */
    public record StatusMatchSubmitBody(
            String competitorProgramName,
            String competitorTierName,
            String requestedTierCode,
            String proofUrl,
            String proofPayloadJson
    ) { }
}
