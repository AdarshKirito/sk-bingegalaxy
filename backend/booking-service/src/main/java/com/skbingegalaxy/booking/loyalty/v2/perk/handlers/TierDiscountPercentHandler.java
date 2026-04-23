package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Perk: {@code TIER_DISCOUNT_PCT}.
 *
 * <p>Silently applies a percentage discount to the booking total for
 * eligible tiers.  Default percentages per tier live in the catalog
 * row's {@code params_json}; admins can override per-binge via
 * {@code loyalty_binge_perk_override.override_params_json}.
 *
 * <p>Example {@code params_json}:
 * <pre>{"SILVER":5,"GOLD":10,"PLATINUM":15,"LIFETIME_PLATINUM":15}</pre>
 *
 * <p>Result is rounded DOWN to 2 decimal places so the member never sees
 * a gifted fractional paisa, and the platform never overpays rounding
 * error at scale.
 */
@Component
@Slf4j
public class TierDiscountPercentHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PARAMS =
            "{\"SILVER\":5,\"GOLD\":10,\"PLATINUM\":15,\"LIFETIME_PLATINUM\":15}";

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_DISCOUNT_PCT; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        if (ctx.bookingAmount() == null || ctx.bookingAmount().signum() <= 0) {
            return PerkOutcome.none();
        }

        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) params = DEFAULT_PARAMS;

        BigDecimal pct;
        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            pct = new BigDecimal(v.toString());
        } catch (Exception e) {
            log.warn("[loyalty-v2] TierDiscountPercentHandler: malformed params_json '{}' — skipping", params);
            return PerkOutcome.none();
        }

        BigDecimal discount = ctx.bookingAmount()
                .multiply(pct)
                .divide(new BigDecimal("100"), 2, RoundingMode.FLOOR);

        return PerkOutcome.discount(discount,
                "Tier " + ctx.membership().getCurrentTierCode() + " discount " + pct + "%");
    }
}
