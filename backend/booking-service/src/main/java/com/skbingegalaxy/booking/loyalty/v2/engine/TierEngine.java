package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

/**
 * Loyalty v2 — TIER engine.
 *
 * <p>Recomputes a member's tier state after any event that could change
 * it (earn, expire, admin adjust, status match, annual rollover).
 *
 * <p><b>Anti-devaluation guarantee:</b> promotions fire immediately.
 * Demotions are DEFERRED until {@code tierEffectiveUntil} — the member
 * enjoys their status through the full validity window even if their
 * qualifying credits drop mid-year.
 *
 * <p><b>Validity window (Bonvoy model):</b> a fresh qualification at
 * instant {@code t} grants the tier through
 * {@code endOfCalendarYear(t) + validityCalendarYearsAfter × 1 year},
 * i.e. "through end of calendar year {@code N} years after qualifying".
 * For {@code validityCalendarYearsAfter = 1} and a qualification in
 * Oct 2026, the member holds that tier through 31 Dec 2027.
 *
 * <p><b>Lifetime tiers:</b> {@code LIFETIME_PLATINUM} has
 * {@code lifetimeCreditsRequired = 250_000} +
 * {@code lifetimeYearsHeldRequired = 10}.  Once achieved, the member
 * is promoted there and never demoted — the {@code tierEffectiveUntil}
 * stays {@code NULL}.
 *
 * <p><b>Soft landing:</b> at annual rollover, a demoting member falls
 * to their tier's {@code softLandingTierCode} (one step down) for one
 * calendar year before any further demotion.  Delta-style.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TierEngine {

    private final LoyaltyConfigService configService;
    private final LoyaltyMembershipRepository membershipRepository;
    private final LoyaltyQualificationEventRepository qcEventRepository;
    private final LoyaltyMembershipEventRepository membershipEventRepository;

    /**
     * Recalculate and persist the tier state for a single membership.
     * Fire immediately on promotions.  Demotions are deferred until
     * the current validity window expires.
     *
     * @return the (possibly updated) membership.
     */
    @Transactional
    public LoyaltyMembership recalculateTier(Long membershipId, LocalDateTime at) {
        LoyaltyMembership m = membershipRepository.findByIdForUpdate(membershipId)
                .orElseThrow(() -> new IllegalStateException("Membership " + membershipId + " not found"));

        LoyaltyProgram program = configService.requireDefaultProgram();

        // Active rolling-window QC sum + all-time sum.
        long windowQc   = qcEventRepository.sumActiveCredits(m.getId(), at);
        long lifetimeQc = qcEventRepository.sumLifetimeCredits(m.getId());
        m.setQualifyingCreditsWindow(windowQc);
        m.setLifetimeCredits(lifetimeQc);

        List<LoyaltyTierDefinition> ladder = configService.activeLadder(program.getId(), at);
        if (ladder.isEmpty()) {
            log.warn("[loyalty-v2] no active tier ladder at {} — leaving membership {} unchanged", at, m.getId());
            return membershipRepository.save(m);
        }

        // Highest tier the member QUALIFIES for right now (ignoring
        // current state).  Walks the ladder from top to bottom.
        LoyaltyTierDefinition qualifiedFor = findQualifiedTier(ladder, windowQc, lifetimeQc, m);

        String currentCode = m.getCurrentTierCode();
        LoyaltyTierDefinition currentDef = ladder.stream()
                .filter(t -> t.getCode().equals(currentCode)).findFirst().orElse(qualifiedFor);

        if (qualifiedFor.getRankOrder() > currentDef.getRankOrder()) {
            // ── PROMOTION (fire immediately) ─────────────────────────────
            promote(m, qualifiedFor, at);
        } else if (qualifiedFor.getRankOrder() < currentDef.getRankOrder()) {
            // ── POTENTIAL DEMOTION (deferred) ────────────────────────────
            // Do nothing to currentTierCode here — the annual rollover job
            // (runAnnualRollover) is the ONLY place demotions happen, and
            // only after tierEffectiveUntil has passed.
            log.debug("[loyalty-v2] membership {} qualifies for lower tier {} but stays at {} until {}",
                    m.getId(), qualifiedFor.getCode(), currentCode, m.getTierEffectiveUntil());
        } else {
            // ── RE-QUALIFICATION at the same tier ────────────────────────
            // Extend the validity window to the new anchor (some programs
            // do this, Bonvoy does not — we choose TO extend, friendlier).
            LocalDateTime newUntil = computeTierExpiry(currentDef, at);
            if (currentDef.getValidityCalendarYearsAfter() != null
                && (m.getTierEffectiveUntil() == null || newUntil.isAfter(m.getTierEffectiveUntil()))) {
                m.setTierEffectiveUntil(newUntil);
                recordEvent(m, "TIER_REQUALIFIED", currentCode, currentCode, at);
            }
        }

        return membershipRepository.save(m);
    }

    // ── Annual rollover (deferred demotion + soft landing) ───────────────

    /**
     * Runs on Feb 1 every year at 03:00 UTC — one month into the new
     * calendar year to give members time to see their new-year status.
     * Processes every membership whose {@code tierEffectiveUntil} has
     * passed and applies demotion (with soft-landing).
     */
    @Scheduled(cron = "0 0 3 1 2 *")         // 03:00 UTC on Feb 1
    @SchedulerLock(name = "loyaltyV2TierRollover", lockAtMostFor = "PT4H", lockAtLeastFor = "PT5M")
    public void runAnnualRolloverJob() {
        runAnnualRollover(LocalDateTime.now());
    }

    public int runAnnualRollover(LocalDateTime at) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        List<LoyaltyMembership> candidates = membershipRepository.findTierExpiringBy(program.getId(), at);
        log.info("[loyalty-v2] annual rollover: {} candidate(s) at {}", candidates.size(), at);

        int demoted = 0;
        for (LoyaltyMembership m : candidates) {
            try {
                if (applyDemotionIfNeeded(m.getId(), at)) demoted++;
            } catch (Exception ex) {
                log.error("[loyalty-v2] rollover failed for membership {}: {}", m.getId(), ex.getMessage(), ex);
            }
        }
        log.info("[loyalty-v2] annual rollover done: {} demoted", demoted);
        return demoted;
    }

    @Transactional
    protected boolean applyDemotionIfNeeded(Long membershipId, LocalDateTime at) {
        LoyaltyMembership m = membershipRepository.findByIdForUpdate(membershipId)
                .orElseThrow(() -> new IllegalStateException("Membership " + membershipId + " missing"));

        if (m.getTierEffectiveUntil() == null) return false;                // permanent (Bronze/Lifetime)
        if (m.getTierEffectiveUntil().isAfter(at)) return false;            // still valid

        LoyaltyProgram program = configService.requireDefaultProgram();
        List<LoyaltyTierDefinition> ladder = configService.activeLadder(program.getId(), at);
        long windowQc = qcEventRepository.sumActiveCredits(m.getId(), at);
        long lifetimeQc = qcEventRepository.sumLifetimeCredits(m.getId());
        LoyaltyTierDefinition qualifiedFor = findQualifiedTier(ladder, windowQc, lifetimeQc, m);

        LoyaltyTierDefinition currentDef = ladder.stream()
                .filter(t -> t.getCode().equals(m.getCurrentTierCode())).findFirst().orElse(null);
        if (currentDef == null) return false;

        if (qualifiedFor.getRankOrder() >= currentDef.getRankOrder()) {
            // Re-qualified for current or higher — extend the window, no demotion.
            LoyaltyTierDefinition target = qualifiedFor.getRankOrder() > currentDef.getRankOrder()
                    ? qualifiedFor : currentDef;
            promote(m, target, at);
            membershipRepository.save(m);
            return false;
        }

        // Apply soft landing — one step down (per tier definition).
        String landingCode = (m.isSoftLandingEligible() && currentDef.getSoftLandingTierCode() != null)
                ? currentDef.getSoftLandingTierCode()
                : qualifiedFor.getCode();

        LoyaltyTierDefinition landingDef = ladder.stream()
                .filter(t -> t.getCode().equals(landingCode))
                .findFirst().orElse(qualifiedFor);

        demote(m, currentDef, landingDef, at);
        membershipRepository.save(m);
        return true;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /** Highest ladder rung the member currently qualifies for (walks top→bottom). */
    private LoyaltyTierDefinition findQualifiedTier(List<LoyaltyTierDefinition> ladder,
                                                    long windowQc, long lifetimeQc,
                                                    LoyaltyMembership m) {
        // Lifetime tiers — checked first (outrank everything).
        for (int i = ladder.size() - 1; i >= 0; i--) {
            LoyaltyTierDefinition t = ladder.get(i);
            if (t.getLifetimeCreditsRequired() != null
                && lifetimeQc >= t.getLifetimeCreditsRequired()
                && (t.getLifetimeYearsHeldRequired() == null
                    || m.getLifetimeYearsAtCurrentTier() >= t.getLifetimeYearsHeldRequired())) {
                return t;
            }
        }
        // Window-qualification tiers — pick highest satisfied by windowQc.
        for (int i = ladder.size() - 1; i >= 0; i--) {
            LoyaltyTierDefinition t = ladder.get(i);
            if (t.getLifetimeCreditsRequired() != null) continue;           // lifetime checked above
            if (windowQc >= t.getQualificationCreditsRequired()) return t;
        }
        return ladder.get(0);                                               // Bronze fallback
    }

    private void promote(LoyaltyMembership m, LoyaltyTierDefinition target, LocalDateTime at) {
        String from = m.getCurrentTierCode();
        m.setCurrentTierCode(target.getCode());
        m.setTierEffectiveFrom(at);
        m.setTierEffectiveUntil(computeTierExpiry(target, at));
        m.setSoftLandingEligible(true);
        recordEvent(m, target.getRankOrder() >= 4 && LoyaltyV2Constants.TIER_LIFETIME_PLATINUM.equals(target.getCode())
                        ? "TIER_LIFETIME_ACHIEVED" : "TIER_UP",
                from, target.getCode(), at);
        log.info("[loyalty-v2] TIER UP membership={} {} → {} valid until {}",
                m.getId(), from, target.getCode(), m.getTierEffectiveUntil());
    }

    private void demote(LoyaltyMembership m, LoyaltyTierDefinition from,
                        LoyaltyTierDefinition to, LocalDateTime at) {
        String fromCode = from.getCode();
        m.setCurrentTierCode(to.getCode());
        m.setTierEffectiveFrom(at);
        m.setTierEffectiveUntil(computeTierExpiry(to, at));
        // One soft landing per fall — cleared until re-qualification.
        m.setSoftLandingEligible(false);
        String eventType = to.getCode().equals(from.getSoftLandingTierCode())
                ? "TIER_SOFT_LANDED" : "TIER_DOWN";
        recordEvent(m, eventType, fromCode, to.getCode(), at);
        log.info("[loyalty-v2] TIER DOWN membership={} {} → {} ({})",
                m.getId(), fromCode, to.getCode(), eventType);
    }

    /**
     * Compute the end of the validity window for this tier, anchored at
     * {@code at}.  NULL result = permanent tier.  Bonvoy rule:
     * "through end of calendar year {@code N} years after qualifying",
     * where N = {@code validityCalendarYearsAfter}.
     */
    private LocalDateTime computeTierExpiry(LoyaltyTierDefinition t, LocalDateTime at) {
        if (t.getValidityCalendarYearsAfter() == null) return null;
        int targetYear = at.getYear() + t.getValidityCalendarYearsAfter();
        return LocalDate.of(targetYear, Month.DECEMBER, 31).atTime(23, 59, 59);
    }

    private void recordEvent(LoyaltyMembership m, String type, String fromCode, String toCode, LocalDateTime at) {
        membershipEventRepository.save(
                LoyaltyMembershipEvent.builder()
                        .tenantId(m.getTenantId())
                        .membershipId(m.getId())
                        .eventType(type)
                        .fromValueJson("{\"tier\":\"" + fromCode + "\"}")
                        .toValueJson("{\"tier\":\"" + toCode + "\",\"validUntil\":\""
                                + (m.getTierEffectiveUntil() == null ? "null" : m.getTierEffectiveUntil()) + "\"}")
                        .triggeredBy("SYSTEM")
                        .build()
        );
    }
}
