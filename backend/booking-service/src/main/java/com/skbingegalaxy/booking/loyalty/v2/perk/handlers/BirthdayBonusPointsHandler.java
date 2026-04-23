package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code BIRTHDAY_BONUS_POINTS}.
 *
 * <p>One-time points credit within a window around the member's
 * birthday.  Actual disbursement is driven by a scheduled job (runs
 * daily, credits every member whose birthday falls in [today-3,
 * today+3] and who hasn't received this year's bonus).  This handler
 * merely answers "what is the bonus amount configured for this tier?"
 *
 * <p>Example {@code params_json}:
 * <pre>{"BRONZE":100,"SILVER":250,"GOLD":500,"PLATINUM":1000,"LIFETIME_PLATINUM":2500}</pre>
 */
@Component
@Slf4j
public class BirthdayBonusPointsHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PARAMS =
            "{\"BRONZE\":100,\"SILVER\":250,\"GOLD\":500,\"PLATINUM\":1000,\"LIFETIME_PLATINUM\":2500}";

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_BIRTHDAY_BONUS; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) params = DEFAULT_PARAMS;

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            long points = Long.parseLong(v.toString());
            return PerkOutcome.bonus(points, "Birthday bonus " + points + " pts");
        } catch (Exception e) {
            log.warn("[loyalty-v2] BirthdayBonusPointsHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
