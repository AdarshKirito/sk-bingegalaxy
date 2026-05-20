package com.skbingegalaxy.payment.provider;

import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discovers every {@link PaymentProvider} bean on the classpath and exposes
 * lookup by name plus a configurable default. Used by
 * {@code PaymentService} so the rest of the codebase never references a
 * specific gateway SDK.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentProviderRegistry {

    private final List<PaymentProvider> providers;

    @Value("${payment.provider.default:razorpay}")
    private String defaultProviderName;

    private Map<String, PaymentProvider> byName;

    private Map<String, PaymentProvider> map() {
        if (byName == null) {
            Map<String, PaymentProvider> m = new HashMap<>();
            for (PaymentProvider p : providers) {
                m.put(p.name().toLowerCase(Locale.ROOT), p);
            }
            byName = Map.copyOf(m);
            log.info("Registered payment providers: {}", byName.keySet());
        }
        return byName;
    }

    public PaymentProvider getDefault() {
        return resolve(defaultProviderName);
    }

    public PaymentProvider resolve(String providerName) {
        String key = (providerName == null || providerName.isBlank()
            ? defaultProviderName : providerName).toLowerCase(Locale.ROOT);
        PaymentProvider p = map().get(key);
        if (p == null) {
            throw new BusinessException("Unknown payment provider: " + providerName);
        }
        return p;
    }

    /**
     * Pick a provider that supports the given currency. Falls back to
     * default when default supports the currency, otherwise picks the first
     * provider that does. Throws when no provider supports the currency.
     */
    public PaymentProvider resolveForCurrency(String preferredProvider, String currency) {
        PaymentProvider preferred = (preferredProvider == null || preferredProvider.isBlank())
            ? getDefault() : resolve(preferredProvider);
        if (preferred.supportsCurrency(currency)) return preferred;

        for (PaymentProvider p : map().values()) {
            if (p.supportsCurrency(currency)) {
                log.warn("Provider '{}' does not support {}; falling back to '{}'",
                    preferred.name(), currency, p.name());
                return p;
            }
        }
        throw new PaymentProvider.UnsupportedCurrencyException(preferred.name(), currency);
    }
}
