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
 * Perk: {@code BONUS_POINTS_MULTIPLIER}.
 *
 * <p>Additional bonus points layered on top of the base earn
 * computation.  This handler is <i>informational</i> — the actual
 * tier multiplier that scales base points is enforced inside
 * {@code EarnEngine} from the tier definition's
 * {@code earnMultiplierPoints}.  This perk delivers an
 * <b>above-and-beyond</b> one-time multiplier (e.g. promo period
 * bonuses) configured per-binge.
 *
 * <p>Example {@code params_json}:
 * <pre>{"extraMultiplier":0.25}</pre>
 * <p>Applied as: {@code bonusPoints = floor(bookingAmount × extraMultiplier)}
 * so a 0.25 multiplier on a ₹1000 booking yields 250 bonus points on
 * top of the base + tier earn.
 */
@Component
@Slf4j
public class BonusPointsMultiplierHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_BONUS_MULTIPLIER; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        if (ctx.bookingAmount() == null || ctx.bookingAmount().signum() <= 0) return PerkOutcome.none();

        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) return PerkOutcome.none();

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get("extraMultiplier");
            if (v == null) return PerkOutcome.none();
            BigDecimal multiplier = new BigDecimal(v.toString());
            if (multiplier.signum() <= 0) return PerkOutcome.none();

            long bonus = ctx.bookingAmount().multiply(multiplier)
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
            return PerkOutcome.bonus(bonus, "Bonus ×" + multiplier + " on ₹" + ctx.bookingAmount());
        } catch (Exception e) {
            log.warn("[loyalty-v2] BonusPointsMultiplierHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
