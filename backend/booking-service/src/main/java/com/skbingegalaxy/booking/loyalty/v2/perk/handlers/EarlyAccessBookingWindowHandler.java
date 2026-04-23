package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code EARLY_ACCESS_BOOKING_WINDOW}.
 *
 * <p>Higher-tier members can book a new binge a configured number of
 * hours before general release.  Availability-service consults this
 * handler when a booking request arrives for an unreleased binge;
 * if the requested booking time falls within the early-access window
 * for the member's tier, the request is allowed through.
 *
 * <p>Example {@code params_json}: <pre>{"GOLD":24,"PLATINUM":48,"LIFETIME_PLATINUM":72}</pre>
 * <p>"Platinum members book 48 hours before general release."
 */
@Component
@Slf4j
public class EarlyAccessBookingWindowHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PARAMS =
            "{\"GOLD\":24,\"PLATINUM\":48,\"LIFETIME_PLATINUM\":72}";

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_EARLY_ACCESS; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) params = DEFAULT_PARAMS;

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            int hours = Integer.parseInt(v.toString());
            return PerkOutcome.priority(hours, "Early-access window " + hours + "h before release");
        } catch (Exception e) {
            log.warn("[loyalty-v2] EarlyAccessBookingWindowHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
