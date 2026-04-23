package com.skbingegalaxy.booking.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
            "eventTypes", "addOns", "activeBinges", "surgeRules",
            // ── Loyalty v2 caches (M5) ────────────────────────────────
            // Evicted atomically by LoyaltyAdminService on any config
            // write.  TTL is short (matches the rest of this manager)
            // so a missed-eviction bug is self-healing within 5 min.
            "loyaltyV2.programs", "loyaltyV2.tiers",
            "loyaltyV2.perks",    "loyaltyV2.bindings");
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES));
        return manager;
    }
}
