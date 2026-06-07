package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Loyalty v2 — enrollment service.
 *
 * <p>Handles every pathway by which a customer enters the program:
 *
 * <ul>
 *   <li>{@code enrollFromBooking(customerId)} — silent enrollment on
 *       first booking.  Awards the welcome bonus immediately so Bronze
 *       members see points on the confirmation screen.</li>
 *   <li>{@code enrollExplicit(customerId, source)} — customer clicks
 *       "Join" in the Membership tab (SSO / form).</li>
 *   <li>{@code enrollFromBackfill(customerId)} — called from the V22
 *       backfill; does NOT re-award the welcome bonus (legacy v1 already
 *       credited those members).</li>
 * </ul>
 *
 * <p><b>Idempotency &amp; concurrency:</b> the method checks for an
 * existing membership before inserting.  If a concurrent request races
 * past the check and the DB {@code UNIQUE(program_id, customer_id)}
 * constraint fires a {@link DataIntegrityViolationException}, the handler
 * catches it and re-reads the row that the other thread inserted — so the
 * caller always gets a valid membership regardless of timing.
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
     * Read-only idempotent lookup — returns existing membership or empty.
     */
    public Optional<LoyaltyMembership> findForCustomer(Long customerId) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        return membershipRepository.findByProgramIdAndCustomerId(program.getId(), customerId);
    }

    /**
     * Enroll-if-absent in a single call.  Used by the earn listener and
     * other components that need "ensure enrolled" semantics.
     */
    @Transactional
    public LoyaltyMembership ensureEnrolledForBooking(Long customerId) {
        return findForCustomer(customerId).orElseGet(() -> enrollFromBooking(customerId));
    }

    // ── Core enrollment ──────────────────────────────────────────────────

    private LoyaltyMembership enroll(Long customerId, String source, boolean awardWelcomeBonus) {
        LoyaltyProgram program = configService.requireDefaultProgram();

        // Fast-path: already enrolled (common case in steady state).
        Optional<LoyaltyMembership> existing =
                membershipRepository.findByProgramIdAndCustomerId(program.getId(), customerId);
        if (existing.isPresent()) {
            log.debug("[loyalty-v2] enroll skipped — customer {} already enrolled as membership {}",
                    customerId, existing.get().getId());
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        try {
            LoyaltyMembership membership = membershipRepository.save(
                    LoyaltyMembership.builder()
                            .programId(program.getId())
                            .customerId(customerId)
                            .memberNumber(memberNumberGenerator.generate())
                            .enrolledAt(now)
                            .enrollmentSource(source)
                            .currentTierCode(LoyaltyV2Constants.TIER_BRONZE)
                            .tierEffectiveFrom(now)
                            .tierEffectiveUntil(null)   // Bronze = permanent
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
                        null, null,
                        now, expiresAt,
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

        } catch (DataIntegrityViolationException ex) {
            // Concurrent enrollment — the DB UNIQUE(program_id, customer_id) constraint
            // fired because another thread completed an enroll between our SELECT and INSERT.
            // Re-read the row the other thread created and return it.
            log.info("[loyalty-v2] concurrent enrollment for customer {} — re-reading existing membership",
                    customerId);
            return membershipRepository.findByProgramIdAndCustomerId(program.getId(), customerId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent enrollment for customer " + customerId
                            + " detected but existing membership not found after retry"));
        }
    }
}
