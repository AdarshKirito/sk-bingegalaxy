package com.skbingegalaxy.booking.tax.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.TaxComputationResult;
import com.skbingegalaxy.booking.entity.TaxRule;
import com.skbingegalaxy.booking.repository.TaxRuleRepository;
import com.skbingegalaxy.common.money.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Default in-house implementation of {@link TaxProvider}. Looks up tax rules
 * from the {@code tax_rules} table and resolves the most specific applicable
 * set via {@link JurisdictionResolver}.
 *
 * <p>Calculation respects:
 *
 * <ul>
 *   <li>Inclusive / exclusive flag (inclusive taxes are extracted from the
 *       gross taxable amount).</li>
 *   <li>{@code applies_to}: TOTAL / BASE / ADDONS / GUEST.</li>
 *   <li>Currency-agnostic BigDecimal math via
 *       {@link com.skbingegalaxy.common.money.MoneyUtil}.</li>
 * </ul>
 *
 * <p>The output stores rich metadata per line (jurisdiction, rule id, formula)
 * so invoices can render the breakdown later without recomputing taxes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalTaxProvider implements TaxProvider {

    public static final String NAME = "INTERNAL";

    private final TaxRuleRepository taxRuleRepository;
    private final ObjectMapper objectMapper;

    @Override public String name() { return NAME; }

    @Override
    public TaxComputationResult computeTaxes(TaxContext ctx,
                                             BigDecimal subtotal,
                                             BigDecimal baseAmount,
                                             BigDecimal addOnAmount,
                                             BigDecimal guestAmount) {
        BigDecimal taxableTotal = MoneyUtil.zeroIfNull(subtotal).max(BigDecimal.ZERO);

        List<TaxRule> candidates = ctx.getBingeId() != null
            ? taxRuleRepository.findActiveForBinge(ctx.getBingeId())
            : taxRuleRepository.findGlobalRules().stream().filter(TaxRule::isActive).toList();

        List<TaxRule> applicable = JurisdictionResolver.filterApplicable(candidates, ctx);

        List<TaxComputationResult.TaxLine> lines = new ArrayList<>();
        BigDecimal totalExclusive = BigDecimal.ZERO;
        BigDecimal totalInclusive = BigDecimal.ZERO;

        for (TaxRule rule : applicable) {
            BigDecimal taxable = switch (rule.getAppliesTo()) {
                case TOTAL  -> taxableTotal;
                case BASE   -> MoneyUtil.zeroIfNull(baseAmount);
                case ADDONS -> MoneyUtil.zeroIfNull(addOnAmount);
                case GUEST  -> MoneyUtil.zeroIfNull(guestAmount);
            };

            TaxRule.CalcMethod method = rule.getCalcMethod() != null
                ? rule.getCalcMethod() : TaxRule.CalcMethod.PERCENT;

            BigDecimal amount;
            String formula;
            Integer units = null;

            if (method == TaxRule.CalcMethod.FLAT_PER_BOOKING
                    || method == TaxRule.CalcMethod.FLAT_PER_HOUR) {
                // Flat fees (county amusement fee, per-hour occupancy charge) apply
                // whenever the rule matches the jurisdiction — even for a booking whose
                // taxable segment is 0 (e.g. fully discounted). They are always
                // exclusive: a flat charge added on top of the price.
                BigDecimal flat = MoneyUtil.zeroIfNull(rule.getFlatAmount());
                if (flat.signum() <= 0) continue;
                if (method == TaxRule.CalcMethod.FLAT_PER_HOUR) {
                    int minutes = ctx != null && ctx.getDurationMinutes() != null
                        ? Math.max(ctx.getDurationMinutes(), 0) : 0;
                    // Round partial hours UP (standard occupancy-tax practice); an
                    // unknown duration charges a single unit so previews never under-quote.
                    units = minutes > 0 ? (minutes + 59) / 60 : 1;
                    amount = flat.multiply(BigDecimal.valueOf(units)).setScale(2, MoneyUtil.ROUND);
                    formula = String.format("%s * %d hour(s)", flat.toPlainString(), units);
                } else {
                    units = 1;
                    amount = flat.setScale(2, MoneyUtil.ROUND);
                    formula = flat.toPlainString() + " flat per booking";
                }
                totalExclusive = totalExclusive.add(amount);
            } else {
                if (taxable.signum() <= 0) continue;
                if (rule.isInclusive()) {
                    amount = MoneyUtil.extractInclusiveTax(taxable, rule.getRateBps())
                        .setScale(2, MoneyUtil.ROUND);
                    totalInclusive = totalInclusive.add(amount);
                    formula = String.format("%s * %d / (10000 + %d)",
                        taxable.toPlainString(), rule.getRateBps(), rule.getRateBps());
                } else {
                    amount = MoneyUtil.applyBps(taxable, rule.getRateBps())
                        .setScale(2, MoneyUtil.ROUND);
                    totalExclusive = totalExclusive.add(amount);
                    formula = String.format("%s * %d / 10000",
                        taxable.toPlainString(), rule.getRateBps());
                }
            }

            lines.add(TaxComputationResult.TaxLine.builder()
                .ruleId(rule.getId())
                .name(rule.getName())
                .rateBps(rule.getRateBps())
                .inclusive(method == TaxRule.CalcMethod.PERCENT && rule.isInclusive())
                .taxableAmount(taxable)
                .amount(amount)
                .taxType(rule.getTaxType())
                .jurisdiction(buildJurisdiction(rule))
                .calcMethod(method.name())
                .flatAmount(rule.getFlatAmount())
                .units(units)
                .formula(formula)
                .build());
        }

        return TaxComputationResult.builder()
            .subtotal(taxableTotal)
            .totalTax(totalExclusive)
            .totalInclusiveTax(totalInclusive)
            .lines(lines)
            .breakdownJson(serialise(lines))
            .provider(NAME)
            .build();
    }

    private static String buildJurisdiction(TaxRule r) {
        StringBuilder sb = new StringBuilder();
        if (r.getCountryCode() != null) sb.append(r.getCountryCode());
        String state = r.getStateCode() != null ? r.getStateCode() : r.getRegionCode();
        if (state != null) sb.append('/').append(state);
        if (r.getCity() != null) sb.append('/').append(r.getCity());
        if (r.getPostalCode() != null) sb.append('/').append(r.getPostalCode());
        return sb.length() == 0 ? "GLOBAL" : sb.toString();
    }

    private String serialise(List<TaxComputationResult.TaxLine> lines) {
        try { return objectMapper.writeValueAsString(lines); }
        catch (JsonProcessingException e) {
            log.warn("Tax breakdown serialisation failed: {}", e.getMessage());
            return "[]";
        }
    }
}
