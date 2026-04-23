package com.skbingegalaxy.booking.loyalty.v2.perk;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loyalty v2 — registry that maps a perk's {@code delivery_handler_key}
 * to its Spring-managed {@link PerkDeliveryHandler} bean.
 *
 * <p>Spring injects ALL beans implementing the handler interface;
 * {@link #init()} builds the lookup table from each bean's
 * {@link PerkDeliveryHandler#handlerKey()}.  Duplicate keys fail loud
 * at startup — safer than silently overwriting a handler.
 */
@Component
@Slf4j
public class PerkRegistry {

    private final List<PerkDeliveryHandler> handlers;
    private final Map<String, PerkDeliveryHandler> byKey = new HashMap<>();

    public PerkRegistry(List<PerkDeliveryHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    void init() {
        for (PerkDeliveryHandler h : handlers) {
            String key = h.handlerKey();
            if (byKey.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate PerkDeliveryHandler registration for key '" + key
                                + "': " + byKey.get(key).getClass().getName()
                                + " and " + h.getClass().getName());
            }
            byKey.put(key, h);
        }
        log.info("[loyalty-v2] perk registry initialized with {} handler(s): {}",
                byKey.size(), byKey.keySet());
    }

    public Optional<PerkDeliveryHandler> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
