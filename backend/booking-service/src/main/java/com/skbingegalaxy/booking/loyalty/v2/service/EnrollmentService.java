package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Loyalty v2 — enrollment service.
 *
 * <p>Handles every pathway by which a customer enters the program:
 *
 * <ul>
 *   <li>{@code enrollFromBooking(customerId)} — silent enrollment on
 *       first booking (no customer UI prompt).  Awards the welcome bonus
 *       immediately so Bronze members see points in their wallet on the
 *       confirmation screen.  This is the <b>instant gratification</b>
 *       promise — a member earns value on their very first interaction.</li>
 *   <li>{@code enrollExplicit(customerId, source)} — customer clicks
 *       "Join" in the Membership tab (SSO / form).</li>
 *   <li>{@code enrollFromBackfill(customerId)} — called from the V22
 *       backfill; does NOT re-award the welcome bonus (the legacy v1
 *       system already credited those members).</li>
 * </ul>
 *
 * <p>Idempotent: calling any variant twice for the same customer is
 * a no-op returning the existing membership.  Uses a pessimistic lookup
 * ({@code findByProgramIdAndCustomerId}) + insert; relies on the
 * {@code UNIQUE(program_id, customer_id)} DB constraint as the final
 * guard.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EnrollmentService {

    private final LoyaltyConfigService configService;
    private final MemberNumberGenerator memberNumberGenerator;
    private final PointsWalletService walletService;

    private final LoyaltyMembershipRepository membershipRepository;
    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyMembershipEventRepository membershipEventRepository;

    // ── Public API ───────────────────────────────────────────────────────

    @Transactional
    public LoyaltyMembership enrollFromBooking(Long customerId) {
        return enroll(customerId, LoyaltyV2Constants.ENROLL_SILENT_BOOKING, true);
    }

    @Transactional
    public LoyaltyMembership enrollExplicit(Long customerId, String source) {
        String src = (source == null || source.isBlank())
                ? LoyaltyV2Constants.ENROLL_EXPLICIT_SIGNUP : source;
        return enroll(customerId, src, true);
    }

    @Transactional
    public LoyaltyMembership enrollFromBackfill(Long customerId) {
        return enroll(customerId, LoyaltyV2Constants.ENROLL_BACKFILL_V2, false);
    }

    /**
     * Idempotent lookup — returns existing membership or empty.
     * Read-only; no transaction required.
     */
    public Optional<LoyaltyMembership> findForCustomer(Long customerId) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        return membershipRepository.findByProgramIdAndCustomerId(program.getId(), customerId);
    }

    /**
     * Safe helper for callers that want "enroll-if-needed" semantics in
     * a single call — e.g. EarnEngine's M6 trigger.
     */
    @Transactional
    public LoyaltyMembership ensureEnrolledForBooking(Long customerId) {
        return findForCustomer(customerId).orElseGet(() -> enrollFromBooking(customerId));
    }

    // ── Core enrollment ──────────────────────────────────────────────────

    private LoyaltyMembership enroll(Long customerId, String source, boolean awardWelcomeBonus) {
        LoyaltyProgram program = configService.requireDefaultProgram();

        Optional<LoyaltyMembership> existing =
                membershipRepository.findByProgramIdAndCustomerId(program.getId(), customerId);
        if (existing.isPresent()) {
            log.debug("[loyalty-v2] enroll skipped — customer {} already enrolled as membership {}",
                    customerId, existing.get().getId());
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now();

        LoyaltyMembership membership = membershipRepository.save(
                LoyaltyMembership.builder()
                        .programId(program.getId())
                        .customerId(customerId)
                        .memberNumber(memberNumberGenerator.generate())
                        .enrolledAt(now)
                        .enrollmentSource(source)
                        .currentTierCode(LoyaltyV2Constants.TIER_BRONZE)
                        .tierEffectiveFrom(now)
                        .tierEffectiveUntil(null)      // Bronze = permanent
                        .softLandingEligible(true)
                        .build()
        );

        LoyaltyPointsWallet wallet = walletRepository.save(
                LoyaltyPointsWallet.builder()
                        .membershipId(membership.getId())
                        .tenantId(membership.getTenantId())
                        .build()
        );

        membershipEventRepository.save(
                LoyaltyMembershipEvent.builder()
                        .tenantId(membership.getTenantId())
                        .membershipId(membership.getId())
                        .eventType("ENROLLED")
                        .toValueJson("{\"source\":\"" + source + "\",\"tier\":\"BRONZE\"}")
                        .triggeredBy(source.startsWith("ADMIN") ? "ADMIN"
                                  : source.startsWith("BACKFILL") ? "SYSTEM" : "CUSTOMER")
                        .build()
        );

        if (awardWelcomeBonus && program.getWelcomeBonusPoints() > 0) {
            LocalDateTime expiresAt = now.plusDays(program.getPointsExpiryDays());
            walletService.credit(new PointsWalletService.CreditRequest(
                    membership.getId(),
                    program.getWelcomeBonusPoints(),
                    LoyaltyV2Constants.LEDGER_BONUS,
                    "BONUS_WELCOME",
                    "welcome",
                    null,
                    null,
                    now,
                    expiresAt,
                    "welcome:membership=" + membership.getId(),
                    null,
                    "WELCOME_BONUS",
                    "Welcome bonus — " + program.getWelcomeBonusPoints() + " points on enrollment",
                    null,
                    "SYSTEM"
            ));
        }

        log.info("[loyalty-v2] ENROLLED customer={} membership={} number={} source={} wallet={}",
                customerId, membership.getId(), membership.getMemberNumber(), source, wallet.getId());
        return membership;
    }
}
