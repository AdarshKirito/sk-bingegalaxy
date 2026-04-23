package com.skbingegalaxy.booking.loyalty.v2.perk.handlers;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.perk.PerkDeliveryHandler;
import org.springframework.stereotype.Component;

/**
 * Perk: {@code WELCOME_BONUS_POINTS}.
 *
 * <p>The welcome-bonus credit is actually issued by
 * {@code EnrollmentService} at enrollment time (using
 * {@code program.welcomeBonusPoints}), not here — this handler only
 * exists so the catalog row has a valid {@code delivery_handler_key}
 * for the admin UI to reference.  Returning {@code PerkOutcome.none()}
 * is correct: by the time the member is booking a binge, the welcome
 * bonus is long since delivered.
 *
 * <p>Keeping a no-op handler registered prevents the {@link com.skbingegalaxy.booking.loyalty.v2.perk.PerkRegistry}
 * from failing on "unknown handler key" when loading catalog rows.
 */
@Component
public class WelcomeBonusPointsHandler implements PerkDeliveryHandler {

    @Override
    public String handlerKey() { return LoyaltyV2Constants.PERK_WELCOME_BONUS; }

    @Override
    public PerkOutcome evaluate(EvaluationContext ctx) {
        return PerkOutcome.none();
    }
}
