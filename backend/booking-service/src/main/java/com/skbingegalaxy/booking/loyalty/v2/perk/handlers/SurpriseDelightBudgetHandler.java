package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code SURPRISE_DELIGHT_BUDGET}.
 *
 * <p>Top-tier members receive occasional unsolicited positive surprises
 * ("thanks for being a member"): a bonus-points gift, a complimentary
 * upgrade, a ₹500 off code, etc.  A scheduled job
 * ({@code SurpriseAndDelightJob}, M7) runs weekly, picks eligible
 * members probabilistically within the budget cap, and issues the gift.
 *
 * <p>This handler returns the per-year budget allotment (₹) configured
 * for the tier — the job uses it to cap total spend.
 *
 * <p>Example {@code params_json}: <pre>{"PLATINUM":500,"LIFETIME_PLATINUM":2000}</pre>
 */
@Component
@Slf4j
public class SurpriseDelightBudgetHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_SURPRISE_DELIGHT; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) return PerkOutcome.none();

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            int budget = Integer.parseInt(v.toString());
            return PerkOutcome.priority(budget, "Surprise-and-delight annual budget ₹" + budget);
        } catch (Exception e) {
            log.warn("[loyalty-v2] SurpriseDelightBudgetHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
