package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Loyalty v2 — READ-ONLY configuration resolver.
 *
 * <p>Centralizes every "what does the config say?" lookup that the
 * engines make on hot paths:
 *
 * <ul>
 *   <li>Which program is default?</li>
 *   <li>What's the active tier ladder right now?</li>
 *   <li>Which earn rule applies to this binge at this tier at this
 *       moment?</li>
 *   <li>Which redeem rule applies to this binge right now?</li>
 *   <li>What perks does a given tier entitle a member to?</li>
 * </ul>
 *
 * <p>Every resolver accepts an explicit {@code at} timestamp so the
 * engines can deterministically re-run past operations (replays, audit
 * reconstructions) using the configuration that was effective at the
 * time of the original event — not today's config.
 *
 * <p>Caching strategy for M1: simple per-call, per-transaction (Hibernate
 * L1 cache handles the within-request case).  M5 adds a proper
 * {@code @Cacheable} layer with eviction on admin edits once the
 * admin-write surfaces exist.  This is intentional — we do not cache
 * ahead of having the invalidation signal wired up.
 *
 * <p><b>This service NEVER writes.</b>  All methods are read-only and
 * the class is annotated {@code @Transactional(readOnly = true)} at
 * the class level.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoyaltyConfigService {

    private final LoyaltyProgramRepository programRepository;
    private final LoyaltyTierDefinitionRepository tierDefinitionRepository;
    private final LoyaltyPerkCatalogRepository perkCatalogRepository;
    private final LoyaltyTierPerkRepository tierPerkRepository;
    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyBingeEarningRuleRepository earningRuleRepository;
    private final LoyaltyBingeRedemptionRuleRepository redemptionRuleRepository;
    private final LoyaltyBingePerkOverrideRepository perkOverrideRepository;

    // ── Program ──────────────────────────────────────────────────────────

    /** Default program ({@code SK_MEMBERSHIP}).  Throws if the seed row is missing — that's a deploy error. */
    @Cacheable("loyaltyV2.programs")
    public LoyaltyProgram requireDefaultProgram() {
        return programRepository.findByCodeAndActiveTrue(LoyaltyV2Constants.DEFAULT_PROGRAM_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Default loyalty program '" + LoyaltyV2Constants.DEFAULT_PROGRAM_CODE
                                + "' is missing — check V21 migration / seed data."));
    }

    public Optional<LoyaltyProgram> findProgramByCode(String code) {
        return programRepository.findByCodeAndActiveTrue(code);
    }

    // ── Tiers ────────────────────────────────────────────────────────────

    // Cached on programId only — 5-minute TTL means membership-grain
    // `at` variation is tolerable (tier definitions don't change
    // effective-date within a 5-minute window in practice).
    @Cacheable(value = "loyaltyV2.tiers", key = "#programId")
    public List<LoyaltyTierDefinition> activeLadder(Long programId, LocalDateTime at) {
        return tierDefinitionRepository.findActiveLadder(programId, at);
    }

    public Optional<LoyaltyTierDefinition> activeTier(Long programId, String code, LocalDateTime at) {
        return tierDefinitionRepository.findActiveByCode(programId, code, at);
    }

    // ── Perks ────────────────────────────────────────────────────────────

    @Cacheable(value = "loyaltyV2.perks", key = "#programId")
    public List<LoyaltyPerkCatalog> activePerks(Long programId, LocalDateTime at) {
        return perkCatalogRepository.findActiveByProgram(programId, at);
    }

    public Optional<LoyaltyPerkCatalog> findPerk(Long programId, String code) {
        return perkCatalogRepository.findByProgramIdAndCode(programId, code);
    }

    /** Perk list entitled to a tier, ordered by {@code sortOrder}. */
    public List<LoyaltyTierPerk> tierPerks(Long tierDefinitionId) {
        return tierPerkRepository.findByTierDefinitionIdOrderBySortOrderAsc(tierDefinitionId);
    }

    // ── Binge binding + rules ────────────────────────────────────────────

    public Optional<LoyaltyBingeBinding> findActiveBinding(Long programId, Long bingeId) {
        return bindingRepository.findActive(programId, bingeId);
    }

    /**
     * Resolve the most specific active earn rule for {@code binding} at
     * {@code at} given member's {@code tierCode}.
     *
     * <p>Specificity ranking (first match wins):
     * <ol>
     *   <li>Tier-specific rule with the latest {@code effectiveFrom}</li>
     *   <li>Universal rule (tierCode = null) with the latest {@code effectiveFrom}</li>
     * </ol>
     * Tie-breaker by latest {@code effectiveFrom} because a newer rule is
     * the more-recent admin intent.
     */
    public Optional<LoyaltyBingeEarningRule> resolveEarningRule(Long bindingId,
                                                                String tierCode,
                                                                LocalDateTime at) {
        List<LoyaltyBingeEarningRule> rules = earningRuleRepository.findActive(bindingId, at);
        if (rules.isEmpty()) return Optional.empty();

        LoyaltyBingeEarningRule best = null;
        for (LoyaltyBingeEarningRule r : rules) {
            boolean tierMatches = r.getTierCode() == null
                    || (tierCode != null && tierCode.equals(r.getTierCode()));
            if (!tierMatches) continue;

            if (best == null) { best = r; continue; }

            // Tier-specific beats universal.
            boolean candidateTierSpecific = r.getTierCode() != null;
            boolean bestTierSpecific      = best.getTierCode() != null;
            if (candidateTierSpecific && !bestTierSpecific) { best = r; continue; }
            if (!candidateTierSpecific &&  bestTierSpecific) continue;

            // Same specificity — pick newer effectiveFrom.
            if (r.getEffectiveFrom().isAfter(best.getEffectiveFrom())) best = r;
        }
        return Optional.ofNullable(best);
    }

    public Optional<LoyaltyBingeRedemptionRule> resolveRedemptionRule(Long bindingId, LocalDateTime at) {
        return redemptionRuleRepository.findActive(bindingId, at);
    }

    /**
     * Effective perk configuration for a binge: for each platform perk,
     * either the catalog default or the binge-specific override.
     * Returns a map keyed by perk code so call sites can do direct lookups.
     */
    public Map<String, PerkResolution> resolvePerksForBinding(LoyaltyBingeBinding binding,
                                                              LocalDateTime at) {
        List<LoyaltyPerkCatalog> catalog = activePerks(binding.getProgramId(), at);
        List<LoyaltyBingePerkOverride> overrides = perkOverrideRepository.findByBindingId(binding.getId());
        Map<Long, LoyaltyBingePerkOverride> overrideByPerkId = new HashMap<>();
        for (LoyaltyBingePerkOverride o : overrides) overrideByPerkId.put(o.getPerkId(), o);

        Map<String, PerkResolution> out = new LinkedHashMap<>();
        for (LoyaltyPerkCatalog p : catalog) {
            LoyaltyBingePerkOverride ov = overrideByPerkId.get(p.getId());
            if (ov != null && "DISABLED".equals(ov.getMode())) continue;

            long cost           = p.getDefaultPointCost();
            int  cooldown       = p.getCooldownHours();
            String paramsJson   = p.getParamsJson();
            if (ov != null && "OVERRIDDEN".equals(ov.getMode())) {
                if (ov.getOverridePointCost() != null)     cost       = ov.getOverridePointCost();
                if (ov.getOverrideCooldownHours() != null) cooldown   = ov.getOverrideCooldownHours();
                if (ov.getOverrideParamsJson() != null)    paramsJson = ov.getOverrideParamsJson();
            }
            out.put(p.getCode(), new PerkResolution(p, cost, cooldown, paramsJson));
        }
        return out;
    }

    /** Snapshot of a perk's effective config at a binge (catalog + any override applied). */
    public record PerkResolution(
            LoyaltyPerkCatalog catalog,
            long   effectivePointCost,
            int    effectiveCooldownHours,
            String effectiveParamsJson
    ) { }
}
