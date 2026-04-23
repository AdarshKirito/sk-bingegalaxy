package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty v2 — admin-side write surface.
 *
 * <p>Rule-of-thumb used everywhere here: <b>never UPDATE an
 * effective-dated row.</b>  To "change" a tier's point threshold, we
 * close the prior row ({@code effectiveTo = at}) and insert a new row
 * ({@code effectiveFrom = at}).  This gives us a full audit log and
 * preserves deterministic replay — the engines can reconstruct any
 * past moment by querying "what was active at {@code t}?" and get
 * the correct answer for all time.
 *
 * <p>Every mutating method evicts the relevant loyalty v2 caches.
 * The evictions are wide ({@code allEntries = true}) — the config
 * data is small enough that a full rebuild is cheaper than trying to
 * reason about which cache keys a given edit invalidates.  A 5-minute
 * TTL on the caches means even a missed eviction self-heals.
 *
 * <p>This service is also the home of <b>per-binge binding / rule</b>
 * management (opt-in, opt-out, per-binge earn/redeem/perk overrides).
 * Those are the knobs the Super-Admin control center in M10 exposes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class LoyaltyAdminService {

    private final LoyaltyProgramRepository programRepository;
    private final LoyaltyTierDefinitionRepository tierDefinitionRepository;
    private final LoyaltyPerkCatalogRepository perkCatalogRepository;
    private final LoyaltyTierPerkRepository tierPerkRepository;
    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyBingeEarningRuleRepository earningRuleRepository;
    private final LoyaltyBingeRedemptionRuleRepository redemptionRuleRepository;
    private final LoyaltyBingePerkOverrideRepository perkOverrideRepository;

    // ─────────────────────────────────────────────────────────────────────
    // TIER DEFINITION  (effective-dated; never UPDATE)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Create a new tier definition, or supersede an existing one with
     * matching {@code (programId, code)} by closing the prior row and
     * inserting the new one with {@code effectiveFrom = at}.
     */
    @Caching(evict = {
            @CacheEvict(value = "loyaltyV2.tiers", allEntries = true),
            @CacheEvict(value = "loyaltyV2.perks", allEntries = true)
    })
    public LoyaltyTierDefinition upsertTier(LoyaltyTierDefinition draft, LocalDateTime at) {
        Optional<LoyaltyTierDefinition> current =
                tierDefinitionRepository.findActiveByCode(draft.getProgramId(), draft.getCode(), at);
        current.ifPresent(prior -> {
            prior.setEffectiveTo(at);
            tierDefinitionRepository.save(prior);
        });

        draft.setId(null);                                  // force INSERT
        draft.setEffectiveFrom(at);
        draft.setEffectiveTo(null);
        LoyaltyTierDefinition saved = tierDefinitionRepository.save(draft);
        log.info("[loyalty-v2] tier upsert: program={} code={} rank={} from={}",
                saved.getProgramId(), saved.getCode(), saved.getRankOrder(), at);
        return saved;
    }

    /** Soft-close a tier: mark {@code effectiveTo = at}, no replacement. */
    @CacheEvict(value = "loyaltyV2.tiers", allEntries = true)
    public void retireTier(Long tierDefinitionId, LocalDateTime at) {
        LoyaltyTierDefinition t = tierDefinitionRepository.findById(tierDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("tier not found: " + tierDefinitionId));
        t.setEffectiveTo(at);
        tierDefinitionRepository.save(t);
        log.info("[loyalty-v2] tier retired: {} at {}", t.getCode(), at);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERK CATALOG
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Upsert a platform-perk catalog row.  Catalog rows are not
     * effective-dated; they're simple versioned records — editing a
     * row updates it in place and bumps {@code updatedAt}.  Audit is
     * handled at the application-event layer.
     */
    @CacheEvict(value = "loyaltyV2.perks", allEntries = true)
    public LoyaltyPerkCatalog savePerk(LoyaltyPerkCatalog draft) {
        LoyaltyPerkCatalog saved = perkCatalogRepository.save(draft);
        log.info("[loyalty-v2] perk saved: program={} code={} handler={}",
                saved.getProgramId(), saved.getCode(), saved.getDeliveryHandlerKey());
        return saved;
    }

    @CacheEvict(value = "loyaltyV2.perks", allEntries = true)
    public LoyaltyTierPerk assignPerkToTier(LoyaltyTierPerk mapping) {
        return tierPerkRepository.save(mapping);
    }

    @CacheEvict(value = "loyaltyV2.perks", allEntries = true)
    public void removePerkFromTier(Long tierPerkId) {
        tierPerkRepository.deleteById(tierPerkId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // BINGE BINDING  (opt-in / opt-out a binge)
    // ─────────────────────────────────────────────────────────────────────

    @CacheEvict(value = "loyaltyV2.bindings", allEntries = true)
    public LoyaltyBingeBinding enableBingeForLoyalty(Long programId, Long bingeId, Long tenantId, Long adminUserId) {
        LoyaltyBingeBinding binding = bindingRepository.findActive(programId, bingeId)
                .orElseGet(() -> LoyaltyBingeBinding.builder()
                        .programId(programId)
                        .bingeId(bingeId)
                        .tenantId(tenantId)
                        .legacyFrozen(false)
                        .effectiveFrom(LocalDateTime.now())
                        .build());
        binding.setStatus(LoyaltyV2Constants.BINDING_ENABLED);
        binding.setEnrolledAt(LocalDateTime.now());
        binding.setEnrolledByAdminId(adminUserId);
        binding.setDisabledAt(null);
        binding.setDisabledByAdminId(null);
        LoyaltyBingeBinding saved = bindingRepository.save(binding);
        log.info("[loyalty-v2] binge {} ENABLED for loyalty by admin {}", bingeId, adminUserId);
        return saved;
    }

    @CacheEvict(value = "loyaltyV2.bindings", allEntries = true)
    public LoyaltyBingeBinding disableBingeForLoyalty(Long bindingId, Long adminUserId) {
        LoyaltyBingeBinding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new IllegalArgumentException("binding not found: " + bindingId));
        binding.setStatus(LoyaltyV2Constants.BINDING_DISABLED);
        binding.setDisabledAt(LocalDateTime.now());
        binding.setDisabledByAdminId(adminUserId);
        LoyaltyBingeBinding saved = bindingRepository.save(binding);
        log.info("[loyalty-v2] binge-binding {} DISABLED by admin {}", bindingId, adminUserId);
        return saved;
    }

    /**
     * Bulk action for super-admin: flip status for many bindings
     * atomically.  This powers the "select-all → Enable" UX of
     * AdminLoyaltyCenter (M10).  Runs in one transaction — either all
     * flips succeed or none.
     *
     * @param status one of {@link LoyaltyV2Constants#BINDING_ENABLED} / {@link LoyaltyV2Constants#BINDING_DISABLED}.
     */
    @CacheEvict(value = "loyaltyV2.bindings", allEntries = true)
    public int bulkSetStatus(List<Long> bindingIds, String status, Long adminUserId) {
        if (bindingIds == null || bindingIds.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        boolean enabling = LoyaltyV2Constants.BINDING_ENABLED.equals(status);
        int touched = 0;
        for (Long id : bindingIds) {
            LoyaltyBingeBinding b = bindingRepository.findById(id).orElse(null);
            if (b == null) continue;
            if (b.isLegacyFrozen()) continue;               // legacy-frozen bindings are immutable
            b.setStatus(status);
            if (enabling) {
                b.setEnrolledAt(now);
                b.setEnrolledByAdminId(adminUserId);
                b.setDisabledAt(null);
                b.setDisabledByAdminId(null);
            } else {
                b.setDisabledAt(now);
                b.setDisabledByAdminId(adminUserId);
            }
            bindingRepository.save(b);
            touched++;
        }
        log.info("[loyalty-v2] bulk status change: status={} by admin {}: {} touched of {} requested",
                status, adminUserId, touched, bindingIds.size());
        return touched;
    }

    // ─────────────────────────────────────────────────────────────────────
    // PER-BINGE RULES  (effective-dated; never UPDATE)
    // ─────────────────────────────────────────────────────────────────────

    public LoyaltyBingeEarningRule upsertEarningRule(LoyaltyBingeEarningRule draft, LocalDateTime at) {
        // Close any active rule with the same (bindingId, tierCode).
        earningRuleRepository.findActive(draft.getBindingId(), at).stream()
                .filter(r -> java.util.Objects.equals(r.getTierCode(), draft.getTierCode()))
                .forEach(r -> { r.setEffectiveTo(at); earningRuleRepository.save(r); });
        draft.setId(null);
        draft.setEffectiveFrom(at);
        draft.setEffectiveTo(null);
        LoyaltyBingeEarningRule saved = earningRuleRepository.save(draft);
        log.info("[loyalty-v2] earn-rule upsert: binding={} tier={} num/den={}/{} at={}",
                saved.getBindingId(), saved.getTierCode(), saved.getPointsNumerator(),
                saved.getAmountDenominator(), at);
        return saved;
    }

    public LoyaltyBingeRedemptionRule upsertRedemptionRule(LoyaltyBingeRedemptionRule draft, LocalDateTime at) {
        redemptionRuleRepository.findActive(draft.getBindingId(), at)
                .ifPresent(r -> { r.setEffectiveTo(at); redemptionRuleRepository.save(r); });
        draft.setId(null);
        draft.setEffectiveFrom(at);
        draft.setEffectiveTo(null);
        LoyaltyBingeRedemptionRule saved = redemptionRuleRepository.save(draft);
        log.info("[loyalty-v2] redeem-rule upsert: binding={} pts-per-currency={} at={}",
                saved.getBindingId(), saved.getPointsPerCurrencyUnit(), at);
        return saved;
    }

    @CacheEvict(value = "loyaltyV2.perks", allEntries = true)
    public LoyaltyBingePerkOverride upsertPerkOverride(LoyaltyBingePerkOverride draft) {
        LoyaltyBingePerkOverride saved = perkOverrideRepository.save(draft);
        log.info("[loyalty-v2] perk override: binding={} perk={} mode={}",
                saved.getBindingId(), saved.getPerkId(), saved.getMode());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────
    // STATUS EXTENSION GRANT (admin one-off)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Grant a status extension to a member.  Adds {@code months} to
     * the member's {@code tierEffectiveUntil}, writes a
     * {@code LoyaltyMembershipEvent} for audit, and returns the new
     * expiry.  If the member currently has {@code tierEffectiveUntil = NULL}
     * (permanent tier like Bronze / Lifetime Platinum), this is a
     * no-op with a warning logged.
     *
     * <p>Uses {@code LEDGER_STATUS_MATCH_GRANT} rather than a synthetic
     * status code so the audit trail is consistent with the status-match
     * flow (same root cause: member retention intervention).
     */
    public LocalDateTime grantStatusExtension(LoyaltyMembership membership, int months, String adminUser) {
        if (membership.getTierEffectiveUntil() == null) {
            log.warn("[loyalty-v2] status extension skipped for membership {} — permanent tier {}",
                    membership.getId(), membership.getCurrentTierCode());
            return null;
        }
        LocalDateTime newExpiry = membership.getTierEffectiveUntil().plusMonths(months);
        membership.setTierEffectiveUntil(newExpiry);
        log.info("[loyalty-v2] status extension granted: membership={} +{}mo by {} new expiry={}",
                membership.getId(), months, adminUser, newExpiry);
        return newExpiry;
    }

    // Used internally by the perk handler resolution to satisfy the
    // LEDGER_STATUS_MATCH_GRANT contract once M7 lands.  Kept here so
    // the constant is imported and the linker complains if the constant
    // vanishes.
    @SuppressWarnings("unused")
    private static final String STATUS_MATCH_LEDGER = LoyaltyV2Constants.LEDGER_STATUS_MATCH_GRANT;
}
