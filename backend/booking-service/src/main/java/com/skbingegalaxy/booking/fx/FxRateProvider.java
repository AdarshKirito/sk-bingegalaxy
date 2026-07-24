package com.skbingegalaxy.booking.fx;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A source of foreign-exchange reference rates.
 *
 * <p>Contract: {@link #fetchRates} returns a map of ISO-4217 code → units of
 * that currency per ONE unit of {@code baseCode} — the exact semantics of
 * {@code CurrencyRate.rateToBase}. Implementations are tried in bean order by
 * {@code FxRateRefreshService}; the first that answers wins, the rest are
 * fallbacks for provider outages.
 */
public interface FxRateProvider {

    /** Short upper-case source tag persisted into {@code currency_rates.fx_source}. */
    String sourceCode();

    /**
     * Fetch the latest reference rates for the given base currency.
     *
     * @throws Exception on any transport/parse/validation failure — the caller
     *         treats every failure identically (log + try next provider).
     */
    Map<String, BigDecimal> fetchRates(String baseCode) throws Exception;
}
