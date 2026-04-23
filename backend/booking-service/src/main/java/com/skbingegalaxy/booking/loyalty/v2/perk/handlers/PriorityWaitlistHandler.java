package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code PRIORITY_WAITLIST}.
 *
 * <p>When a binge is sold out and spots open via cancellations, the
 * waitlist is drained in priority-score order.  This handler contributes
 * a tier-dependent boost that availability-service applies on top of
 * the base FIFO ordering.
 *
 * <p>Example {@code params_json}: <pre>{"SILVER":10,"GOLD":50,"PLATINUM":200,"LIFETIME_PLATINUM":500}</pre>
 * <p>Higher scores = drained earlier.  A Platinum member will always
 * jump ahead of Silver even if they joined the waitlist later.
 */
@Component
@Slf4j
public class PriorityWaitlistHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PARAMS =
            "{\"SILVER\":10,\"GOLD\":50,\"PLATINUM\":200,\"LIFETIME_PLATINUM\":500}";

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_PRIORITY_WAITLIST; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) params = DEFAULT_PARAMS;

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            int boost = Integer.parseInt(v.toString());
            return PerkOutcome.priority(boost, "Waitlist priority +" + boost);
        } catch (Exception e) {
            log.warn("[loyalty-v2] PriorityWaitlistHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
