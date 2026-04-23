package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import org.springframework.stereotype.Component;

/**
 * Perk: {@code REWARD_CATALOG_CLAIM}.
 *
 * <p>The reward-catalog flow (member burns points for a curated reward
 * from {@code loyalty_binge_reward_items}) is orchestrated by a
 * dedicated {@code RewardClaimService} — not in the generic perk
 * evaluation path.  This handler registers the key so the catalog
 * row is valid; the actual claim logic lives in M5/M8.
 */
@Component
public class RewardCatalogClaimHandler implements PerkDeliveryHandler {

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_REWARD_CATALOG; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        return PerkOutcome.none();
    }
}
