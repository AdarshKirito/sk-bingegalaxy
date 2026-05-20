package com.skbingegalaxy.booking.loyalty.v2.config;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeBinding;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeEarningRule;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyBingeRedemptionRule;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyProgram;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyBingeBindingRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyBingeEarningRuleRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyBingeRedemptionRuleRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.repository.BingeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Loyalty v2 — startup auto-seeder for per-binge bindings + earn rules.
 *
 * <p>WHY THIS EXISTS
 * <p>Without an active {@link LoyaltyBingeBinding} + matching
 * {@link LoyaltyBingeEarningRule}, {@code EarnEngine.earnForBooking}
 * silently exits with {@code skipped("NO_BINDING")} or
 * {@code skipped("NO_EARN_RULE")}.  In production we observed several
 * COMPLETED bookings where loyalty points were never credited because
 * a newly-created binge had no rows in either table.
 *
 * <p>Defense-in-depth: this runner ensures every binge has a default
 * <b>ENABLED</b> binding and a universal {@code FLAT_PER_AMOUNT} rule
 * (10 pts / ₹1, 1.0× tier and QC multipliers) on every application
 * boot.  If an admin has explicitly DISABLED a binge or set custom
 * rules, those are preserved untouched (we only INSERT for binges
 * that lack any binding row).
 *
 * <p>This is the same pattern Marriott Bonvoy / Hilton Honors use to
 * onboard new properties — every property is auto-bound to the program
 * with the chain-default earn rate, and brand teams override only when
 * they need a custom multiplier.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Order(50) // run after Flyway and other schema init
public class LoyaltyBindingAutoSeeder implements ApplicationRunner {

    /** Chain-default earn rate for new binges. */
    private static final long DEFAULT_POINTS_NUMERATOR = 10L;
    private static final BigDecimal DEFAULT_AMOUNT_DENOMINATOR = new BigDecimal("1.00");
    private static final long DEFAULT_POINTS_PER_CURRENCY_UNIT = 100L;
    private static final long DEFAULT_MIN_REDEMPTION_POINTS = 100L;
    private static final BigDecimal DEFAULT_MAX_REDEMPTION_PERCENT = new BigDecimal("50.00");

    private final BingeRepository bingeRepository;
    private final LoyaltyConfigService configService;
    private final LoyaltyBingeBindingRepository bindingRepository;
    private final LoyaltyBingeEarningRuleRepository earningRuleRepository;
    private final LoyaltyBingeRedemptionRuleRepository redemptionRuleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LoyaltyProgram program;
        try {
            program = configService.requireDefaultProgram();
        } catch (Exception ex) {
            log.warn("[loyalty-v2] auto-seed skipped — default loyalty program not yet provisioned: {}",
                    ex.getMessage());
            return;
        }

        List<Binge> binges = bingeRepository.findAll();
        LocalDateTime now = LocalDateTime.now();
        int seededBindings = 0;
        int seededRules = 0;
        int seededRedemptionRules = 0;
        int thawedLegacyBindings = 0;

        for (Binge binge : binges) {
            if (binge.getId() == null) continue;

            LoyaltyBingeBinding binding = bindingRepository
                    .findByProgramIdAndBingeId(program.getId(), binge.getId())
                    .orElse(null);

            if (binding == null) {
                binding = LoyaltyBingeBinding.builder()
                        .programId(program.getId())
                        .bingeId(binge.getId())
                        .tenantId(program.getTenantId())
                        .status(LoyaltyV2Constants.BINDING_ENABLED)
                        .legacyFrozen(false)
                        .enrolledAt(now)
                        .effectiveFrom(now)
                        .build();
                binding = bindingRepository.save(binding);
                seededBindings++;
                log.info("[loyalty-v2] auto-seeded ENABLED binding for binge {} ({})",
                        binge.getId(), binge.getName());
            } else if (binding.isLegacyFrozen()
                    && LoyaltyV2Constants.BINDING_ENABLED_LEGACY.equals(binding.getStatus())) {
                binding.setStatus(LoyaltyV2Constants.BINDING_ENABLED);
                binding.setLegacyFrozen(false);
                binding.setEnrolledAt(binding.getEnrolledAt() == null ? now : binding.getEnrolledAt());
                binding = bindingRepository.save(binding);
                thawedLegacyBindings++;
                log.info("[loyalty-v2] thawed legacy binding for binge {} ({}) after v1 cutover",
                        binge.getId(), binge.getName());
            }

            // Only seed a rule if the binding has no active universal rule.
            List<LoyaltyBingeEarningRule> activeRules =
                    earningRuleRepository.findActive(binding.getId(), now);
            boolean hasUniversalRule = activeRules.stream()
                    .anyMatch(r -> r.getTierCode() == null);
            if (!hasUniversalRule) {
                LoyaltyBingeEarningRule rule = LoyaltyBingeEarningRule.builder()
                        .bindingId(binding.getId())
                        .tenantId(program.getTenantId())
                        .tierCode(null)
                        .ruleType("FLAT_PER_AMOUNT")
                        .pointsNumerator(DEFAULT_POINTS_NUMERATOR)
                        .amountDenominator(DEFAULT_AMOUNT_DENOMINATOR)
                        .tierMultiplier(BigDecimal.ONE)
                        .qcMultiplier(BigDecimal.ONE)
                        .effectiveFrom(now)
                        .build();
                earningRuleRepository.save(rule);
                seededRules++;
                log.info("[loyalty-v2] auto-seeded universal earn rule for binding {} (binge {})",
                        binding.getId(), binge.getId());
            }

            if (redemptionRuleRepository.findActive(binding.getId(), now).isEmpty()) {
                LoyaltyBingeRedemptionRule redeemRule = LoyaltyBingeRedemptionRule.builder()
                        .bindingId(binding.getId())
                        .tenantId(program.getTenantId())
                        .pointsPerCurrencyUnit(DEFAULT_POINTS_PER_CURRENCY_UNIT)
                        .minRedemptionPoints(DEFAULT_MIN_REDEMPTION_POINTS)
                        .maxRedemptionPercent(DEFAULT_MAX_REDEMPTION_PERCENT)
                        .effectiveFrom(now)
                        .build();
                redemptionRuleRepository.save(redeemRule);
                seededRedemptionRules++;
                log.info("[loyalty-v2] auto-seeded redemption rule for binding {} (binge {})",
                        binding.getId(), binge.getId());
            }
        }

        if (seededBindings > 0 || seededRules > 0 || seededRedemptionRules > 0 || thawedLegacyBindings > 0) {
            log.info("[loyalty-v2] auto-seed complete — bindings={} rules={} (over {} binges)",
                    seededBindings, seededRules, binges.size());
            log.info("[loyalty-v2] auto-seed details: thawedLegacyBindings={} redemptionRules={}",
                    thawedLegacyBindings, seededRedemptionRules);
        } else {
            log.debug("[loyalty-v2] auto-seed: every binge already has binding + universal earn/redeem rules");
        }
    }
}
