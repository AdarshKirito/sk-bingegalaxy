package com.skbingegalaxy.booking.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.loyalty")
@Getter
@Setter
public class LoyaltyProperties {

    /** Master toggle for the loyalty programme. */
    private boolean enabled = true;

    /** How many loyalty points customers earn per ₹1 spent. */
    private int pointsPerRupee = 10;

    /** Points value when redeemed: how many points = ₹1 discount. */
    private int redemptionRate = 100;

    /**
     * M12 cutover flag — when <code>true</code>, the legacy v1 earn /
     * redeem / expire paths short-circuit and the v2 engines become the
     * sole source of truth.  During the Phase-1 shadow period this stays
     * <code>false</code> so both systems run in parallel and an ops
     * comparison job can verify parity.
     *
     * <p>Flip via <code>APP_LOYALTY_V2_PRIMARY=true</code> or
     * <code>app.loyalty.v2-primary=true</code>.
     */
    private boolean v2Primary = false;
}
