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
}
