package com.skbingegalaxy.booking.tax.provider;

import com.skbingegalaxy.booking.entity.TaxRule;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the most relevant tax rules from a candidate set, given a
 * {@link TaxContext}. Resolution priority (from most specific to least):
 *
 * <ol>
 *   <li>Binge-scoped rule (binge_id set)</li>
 *   <li>Postal-code match</li>
 *   <li>City match</li>
 *   <li>State match</li>
 *   <li>Country match</li>
 *   <li>Product type match (when no geographic match)</li>
 *   <li>Customer type match</li>
 *   <li>Default fallback (no jurisdiction fields set)</li>
 * </ol>
 *
 * Effective-date windows (effective_from/effective_to) are honoured: a rule
 * whose window does not contain "now" is skipped silently.
 */
public final class JurisdictionResolver {

    private JurisdictionResolver() { /* utility */ }

    /**
     * Score a rule against a context. Higher score = more specific.
     * Returns {@code -1} when the rule does not match the context at all
     * (e.g. country mismatch) — the caller should drop those rules.
     */
    public static int score(TaxRule rule, TaxContext ctx) {
        // Effective-date window
        LocalDateTime now = LocalDateTime.now();
        if (rule.getEffectiveFrom() != null && now.isBefore(rule.getEffectiveFrom())) return -1;
        if (rule.getEffectiveTo()   != null && now.isAfter(rule.getEffectiveTo()))   return -1;

        int score = 0;

        // Binge scope: binge-scoped rule against the right binge always beats global.
        if (rule.getBingeId() != null) {
            if (ctx.getBingeId() == null || !ctx.getBingeId().equals(rule.getBingeId())) return -1;
            score += 1000;
        }

        // Country: hard filter — if rule sets country and context has a different country,
        // the rule does NOT apply.
        if (notBlank(rule.getCountryCode())) {
            if (!equalsIgnoreCaseSafe(rule.getCountryCode(), ctx.resolvedCountry())) return -1;
            score += 100;
        }

        // State / region (region_code retained as legacy alias for state)
        String ruleState = firstNonBlank(rule.getStateCode(), rule.getRegionCode());
        if (notBlank(ruleState)) {
            if (!equalsIgnoreCaseSafe(ruleState, ctx.resolvedState())) return -1;
            score += 50;
        }

        if (notBlank(rule.getCity())) {
            if (!equalsIgnoreCaseSafe(rule.getCity(), ctx.resolvedCity())) return -1;
            score += 30;
        }

        if (notBlank(rule.getPostalCode())) {
            if (!equalsIgnoreCaseSafe(rule.getPostalCode(), ctx.resolvedPostal())) return -1;
            score += 80; // postal is more specific than city
        }

        // Product type
        if (notBlank(rule.getProductType()) && !"ALL".equalsIgnoreCase(rule.getProductType())) {
            if (ctx.getProductType() == null || !rule.getProductType().equalsIgnoreCase(ctx.getProductType())) {
                return -1;
            }
            score += 10;
        }

        // Customer type
        if (notBlank(rule.getCustomerType()) && !"ALL".equalsIgnoreCase(rule.getCustomerType())) {
            if (ctx.getCustomerType() == null || !rule.getCustomerType().equalsIgnoreCase(ctx.getCustomerType())) {
                return -1;
            }
            score += 5;
        }

        return score;
    }

    /**
     * From a candidate list, return the rules that should actually apply.
     * Same-named rules with overlapping jurisdiction collapse to the one
     * with the highest score (binge-scoped &gt; geographic specifics).
     */
    public static List<TaxRule> filterApplicable(List<TaxRule> candidates, TaxContext ctx) {
        java.util.Map<String, TaxRule> byKey = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> scoreByKey = new java.util.HashMap<>();
        for (TaxRule rule : candidates) {
            int s = score(rule, ctx);
            if (s < 0) continue;
            String key = (rule.getName() == null ? "" : rule.getName().toLowerCase()) + "|" +
                         (rule.getTaxType() == null ? "" : rule.getTaxType().toLowerCase());
            Integer existing = scoreByKey.get(key);
            if (existing == null || s > existing) {
                byKey.put(key, rule);
                scoreByKey.put(key, s);
            }
        }
        return byKey.values().stream()
            .sorted(Comparator.comparingInt(TaxRule::getPriority))
            .toList();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
    private static String firstNonBlank(String a, String b) {
        return notBlank(a) ? a : b;
    }
}
