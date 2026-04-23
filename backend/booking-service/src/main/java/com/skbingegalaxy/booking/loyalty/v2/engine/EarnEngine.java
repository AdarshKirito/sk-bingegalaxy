package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Loyalty v2 — EARN engine.
 *
 * <p>Given a completed booking, compute and persist both sides of the
 * earning model:
 *
 * <ul>
 *   <li>REDEEMABLE POINTS — sent through {@link PointsWalletService#credit}
 *       which creates a FIFO lot + ledger entry.</li>
 *   <li>QUALIFYING CREDITS — written as a
 *       {@link LoyaltyQualificationEvent} with a rolling-window expiry
 *       ({@code eventAt + program.qualificationWindowDays}).  Drives
 *       {@link TierEngine} recalculation.</li>
 * </ul>
 *
 * <p>Both derive from the same binge earning rule but can use different
 * multipliers ({@code tierMultiplier} for points, {@code qcMultiplier}
 * for credits).  This is what lets a "luxury" binge reward status faster
 * than redeemable points, Bonvoy-style.
 *
 * <p>Earning skips silently (logs at INFO, returns an empty result) when:
 * <ul>
 *   <li>no binge binding exists or it's {@code DISABLED}</li>
 *   <li>binding is {@code ENABLED_LEGACY} + {@code legacyFrozen=true} —
 *       the legacy (v1) {@link com.skbingegalaxy.booking.service.LoyaltyService}
 *       stays authoritative for that binge during dual-write;</li>
 *   <li>no active earn rule matches (all universal + tier-specific
 *       rules filtered out by {@code effectiveFrom/To});</li>
 *   <li>booking amount is below {@code minBookingAmount}.</li>
 * </ul>
 *
 * <p>Does NOT recompute tier — that's handed off to {@link TierEngine}
 * after the earn succeeds.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EarnEngine {

    private final LoyaltyConfigService configService;
    private final PointsWalletService walletService;
    private final LoyaltyMembershipRepository membershipRepository;
    private final LoyaltyQualificationEventRepository qcEventRepository;
    private final TierEngine tierEngine;

    @Transactional
    public EarnResult earnForBooking(EarnRequest req) {
        LoyaltyMembership membership = membershipRepository.findById(req.membershipId())
                .orElseThrow(() -> new IllegalStateException("Membership " + req.membershipId() + " not found"));

        LoyaltyProgram program = configService.requireDefaultProgram();

        Optional<LoyaltyBingeBinding> bindingOpt = configService.findActiveBinding(program.getId(), req.bingeId());
        if (bindingOpt.isEmpty()) {
            log.debug("[loyalty-v2] earn skipped — no active binding for binge {}", req.bingeId());
            return EarnResult.skipped("NO_BINDING");
        }
        LoyaltyBingeBinding binding = bindingOpt.get();

        if (binding.isLegacyFrozen()) {
            log.debug("[loyalty-v2] earn skipped — binge {} is ENABLED_LEGACY + frozen (v1 authoritative)", req.bingeId());
            return EarnResult.skipped("LEGACY_FROZEN");
        }

        Optional<LoyaltyBingeEarningRule> ruleOpt = configService.resolveEarningRule(
                binding.getId(), membership.getCurrentTierCode(), req.at());
        if (ruleOpt.isEmpty()) {
            log.info("[loyalty-v2] earn skipped — no active earn rule for binding {} tier {}",
                    binding.getId(), membership.getCurrentTierCode());
            return EarnResult.skipped("NO_EARN_RULE");
        }
        LoyaltyBingeEarningRule rule = ruleOpt.get();

        if (rule.getMinBookingAmount() != null
                && req.bookingAmount().compareTo(rule.getMinBookingAmount()) < 0) {
            log.debug("[loyalty-v2] earn skipped — booking amount {} below min {}",
                    req.bookingAmount(), rule.getMinBookingAmount());
            return EarnResult.skipped("BELOW_MIN_AMOUNT");
        }

        long rawPoints = computePoints(rule, req.bookingAmount());
        long boostedPoints = applyTierMultiplier(rawPoints, rule.getTierMultiplier());
        long capped = applyCap(boostedPoints, rule.getCapPerBooking());

        long qcRaw = rawPoints;  // credits base off the same pre-multiplier points
        long qualifyingCredits = applyTierMultiplier(qcRaw, rule.getQcMultiplier());

        if (capped <= 0 && qualifyingCredits <= 0) {
            return EarnResult.skipped("ZERO_POINTS");
        }

        LocalDateTime expiresAt = req.at().plusDays(program.getPointsExpiryDays());

        LoyaltyLedgerEntry ledgerEntry = null;
        if (capped > 0) {
            ledgerEntry = walletService.credit(new PointsWalletService.CreditRequest(
                    membership.getId(),
                    capped,
                    LoyaltyV2Constants.LEDGER_EARN,
                    "EARN_BOOKING",
                    req.bookingRef(),
                    req.bingeId(),
                    req.bookingRef(),
                    req.at(),
                    expiresAt,
                    "earn:booking=" + req.bookingRef(),
                    req.correlationId(),
                    "BOOKING_COMPLETED",
                    "Earn for booking " + req.bookingRef()
                            + " (amount=" + req.bookingAmount()
                            + ", rule=" + rule.getId() + ")",
                    null,
                    "SYSTEM"
            ));
        }

        LoyaltyQualificationEvent qcEvent = null;
        if (qualifyingCredits > 0) {
            qcEvent = persistQualificationEvent(membership, binding, rule, req, qualifyingCredits, program);
        }

        // Recalc tier AFTER both writes commit (so sum sees them).  We are
        // inside a single @Transactional here so the TierEngine will read
        // the freshly-persisted QC via the same tx snapshot.
        tierEngine.recalculateTier(membership.getId(), req.at());

        log.info("[loyalty-v2] EARN booking={} membership={} points={} qc={} binding={} rule={}",
                req.bookingRef(), membership.getId(), capped, qualifyingCredits, binding.getId(), rule.getId());

        return EarnResult.awarded(capped, qualifyingCredits,
                ledgerEntry != null ? ledgerEntry.getId() : null,
                qcEvent != null ? qcEvent.getId() : null);
    }

    // ── Computation helpers ──────────────────────────────────────────────

    /** Raw (pre-multiplier) points from rule. {@code floor(amount * num / den)}. */
    private long computePoints(LoyaltyBingeEarningRule rule, BigDecimal bookingAmount) {
        BigDecimal ratio = new BigDecimal(rule.getPointsNumerator())
                .divide(rule.getAmountDenominator(), 6, RoundingMode.HALF_UP);
        BigDecimal raw = bookingAmount.multiply(ratio);
        return raw.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private long applyTierMultiplier(long points, BigDecimal multiplier) {
        if (multiplier == null || BigDecimal.ONE.compareTo(multiplier) == 0) return points;
        BigDecimal scaled = BigDecimal.valueOf(points).multiply(multiplier);
        return scaled.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private long applyCap(long points, Long cap) {
        if (cap == null || cap <= 0) return points;
        return Math.min(points, cap);
    }

    private LoyaltyQualificationEvent persistQualificationEvent(
            LoyaltyMembership membership, LoyaltyBingeBinding binding,
            LoyaltyBingeEarningRule rule, EarnRequest req, long qc, LoyaltyProgram program) {

        // Guard against double-write when event replays: our unique index
        // isn't there (qualification events are append-only and meant to
        // support reversals), so we de-dupe in-app per (membership, booking).
        var existing = qcEventRepository.findByMembershipIdAndBookingRef(membership.getId(), req.bookingRef());
        if (!existing.isEmpty()) {
            log.info("[loyalty-v2] QC event already exists for booking {} — skipping", req.bookingRef());
            return existing.get(0);
        }

        LocalDateTime windowExpiry = req.at().plusDays(
                rule.getEffectiveTo() != null
                        ? Math.min(program.getPointsExpiryDays(), 365)
                        : 365
        );
        // Default window = 365d (rolling 12-month).  Tier engine uses this
        // to compute "credits earned in the last N days".

        return qcEventRepository.save(
                LoyaltyQualificationEvent.builder()
                        .tenantId(membership.getTenantId())
                        .membershipId(membership.getId())
                        .bingeId(binding.getBingeId())
                        .bookingRef(req.bookingRef())
                        .eventType("BOOKING_COMPLETED")
                        .qualificationCredits(qc)
                        .eventAt(req.at())
                        .expiresFromWindowAt(windowExpiry)
                        .build()
        );
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record EarnRequest(
            Long membershipId,
            Long bingeId,
            String bookingRef,
            BigDecimal bookingAmount,
            LocalDateTime at,
            String correlationId
    ) { }

    public record EarnResult(
            boolean awarded,
            long pointsAwarded,
            long qualifyingCreditsAwarded,
            Long ledgerEntryId,
            Long qualificationEventId,
            String skipReason
    ) {
        public static EarnResult awarded(long pts, long qc, Long ledgerId, Long qcId) {
            return new EarnResult(true, pts, qc, ledgerId, qcId, null);
        }
        public static EarnResult skipped(String reason) {
            return new EarnResult(false, 0, 0, null, null, reason);
        }
    }
}
