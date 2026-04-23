package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code FREE_CANCELLATION_EXTENDED}.
 *
 * <p>Extends the default cancellation grace window for higher-tier
 * members.  This handler does <b>not</b> refund on its own — the
 * booking-service cancellation code asks us: "given this member and
 * binge, what cancellation grace hours should apply?"  We return the
 * answer as a {@code priorityBoost} (hours as a positive int) so the
 * caller can compare it against its default and use the better of
 * the two.
 *
 * <p>Example {@code params_json}:
 * <pre>{"SILVER":24,"GOLD":48,"PLATINUM":72,"LIFETIME_PLATINUM":168}</pre>
 * <p>Semantics: a Platinum member booking a binge with this perk
 * enabled gets a 72-hour free-cancellation window regardless of the
 * binge's default 24-hour policy.
 */
@Component
@Slf4j
public class FreeCancellationExtendedHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PARAMS =
            "{\"SILVER\":24,\"GOLD\":48,\"PLATINUM\":72,\"LIFETIME_PLATINUM\":168}";

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_FREE_CANCEL_24H; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) params = DEFAULT_PARAMS;

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get(ctx.membership().getCurrentTierCode());
            if (v == null) return PerkOutcome.none();
            int hours = Integer.parseInt(v.toString());
            return PerkOutcome.priority(hours, "Free cancellation window " + hours + "h");
        } catch (Exception e) {
            log.warn("[loyalty-v2] FreeCancellationExtendedHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
