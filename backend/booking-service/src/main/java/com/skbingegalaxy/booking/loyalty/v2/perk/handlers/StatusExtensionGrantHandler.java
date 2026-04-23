package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Perk: {@code STATUS_EXTENSION_GRANT}.
 *
 * <p>A one-time extension of {@code tier_effective_until} granted to
 * retain high-value members facing demotion.  Admin-initiated: the
 * admin control panel clicks "Extend status" and we append N months
 * to the member's current tier validity.  This handler reports the
 * configured extension length so the admin UI can preview the new
 * expiry date.
 *
 * <p>Example {@code params_json}: <pre>{"extensionMonths":12}</pre>
 * <p>Used by {@code LoyaltyAdminService} (M5); no booking-time effect.
 */
@Component
@Slf4j
public class StatusExtensionGrantHandler implements PerkDeliveryHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_STATUS_EXTENSION; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        String params = ctx.perk().effectiveParamsJson();
        if (params == null || params.isBlank()) return PerkOutcome.none();

        try {
            Map<String, Object> map = MAPPER.readValue(params, Map.class);
            Object v = map.get("extensionMonths");
            if (v == null) return PerkOutcome.none();
            int months = Integer.parseInt(v.toString());
            return PerkOutcome.priority(months, "Status extension +" + months + "mo available");
        } catch (Exception e) {
            log.warn("[loyalty-v2] StatusExtensionGrantHandler: malformed params_json '{}'", params);
            return PerkOutcome.none();
        }
    }
}
