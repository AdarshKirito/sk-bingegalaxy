package com.skbingegalaxy.booking.util;

import java.util.Map;

/**
 * Maps an ISO-3166-1 alpha-2 country code to its primary ISO-4217 currency code.
 *
 * <p>A binge's currency is derived from its country — the customer never chooses a
 * currency; every price, tax and payment for that binge is denominated in the country's
 * currency. This is the single source of truth for that derivation. Unknown/blank
 * countries fall back to the platform base ({@code INR}) so a binge is never left with a
 * null currency.
 *
 * <p>Only the "primary" currency is mapped (e.g. US → USD); multi-currency territories
 * pick the dominant legal-tender currency. Extend the map as new markets are onboarded.
 */
public final class CountryCurrency {

    /** Platform base / fallback currency. */
    public static final String BASE = "INR";

    private CountryCurrency() {}

    private static final Map<String, String> MAP = Map.ofEntries(
        Map.entry("IN", "INR"), Map.entry("US", "USD"), Map.entry("GB", "GBP"),
        Map.entry("CN", "CNY"), Map.entry("JP", "JPY"), Map.entry("AE", "AED"),
        Map.entry("SG", "SGD"), Map.entry("AU", "AUD"), Map.entry("CA", "CAD"),
        Map.entry("CH", "CHF"), Map.entry("SA", "SAR"), Map.entry("HK", "HKD"),
        Map.entry("MY", "MYR"), Map.entry("TH", "THB"), Map.entry("ID", "IDR"),
        Map.entry("PH", "PHP"), Map.entry("VN", "VND"), Map.entry("KR", "KRW"),
        Map.entry("NZ", "NZD"), Map.entry("ZA", "ZAR"), Map.entry("BR", "BRL"),
        Map.entry("MX", "MXN"), Map.entry("RU", "RUB"), Map.entry("TR", "TRY"),
        Map.entry("QA", "QAR"), Map.entry("KW", "KWD"), Map.entry("BH", "BHD"),
        Map.entry("OM", "OMR"), Map.entry("LK", "LKR"), Map.entry("NP", "NPR"),
        Map.entry("BD", "BDT"), Map.entry("PK", "PKR"), Map.entry("EG", "EGP"),
        Map.entry("NG", "NGN"), Map.entry("KE", "KES"), Map.entry("SE", "SEK"),
        Map.entry("NO", "NOK"), Map.entry("DK", "DKK"), Map.entry("PL", "PLN"),
        // Eurozone members
        Map.entry("DE", "EUR"), Map.entry("FR", "EUR"), Map.entry("IT", "EUR"),
        Map.entry("ES", "EUR"), Map.entry("NL", "EUR"), Map.entry("IE", "EUR"),
        Map.entry("PT", "EUR"), Map.entry("BE", "EUR"), Map.entry("AT", "EUR"),
        Map.entry("GR", "EUR"), Map.entry("FI", "EUR"), Map.entry("LU", "EUR")
    );

    /**
     * @param countryIso2 ISO-3166-1 alpha-2 code (case-insensitive), may be null/blank
     * @return the country's ISO-4217 currency code, or {@link #BASE} when unknown
     */
    public static String forCountry(String countryIso2) {
        if (countryIso2 == null || countryIso2.isBlank()) return BASE;
        return MAP.getOrDefault(countryIso2.trim().toUpperCase(), BASE);
    }
}
