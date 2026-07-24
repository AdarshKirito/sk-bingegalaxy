package com.skbingegalaxy.booking.util;

import java.util.Map;

/**
 * Default consumption-tax template per ISO-3166 alpha-2 country, used to
 * auto-assign a venue-scoped {@link com.skbingegalaxy.booking.entity.TaxRule}
 * when a binge is created (or moved to a new country). Rates are the standard
 * national rate for entertainment/services at the time of writing; venue admins
 * and super-admins can adjust or deactivate the seeded rule from the tax console.
 *
 * <p>Countries with no national-level consumption tax on services (e.g. US, where
 * sales tax is state-administered and highly local) intentionally have NO entry —
 * nothing is seeded and the venue starts tax-free until rules are configured.
 */
public final class CountryTaxDefaults {

    /** A seedable default: display name, invoice tax type, rate in basis points. */
    public record Template(String name, String taxType, int rateBps) {}

    private static final Map<String, Template> DEFAULTS = Map.ofEntries(
        Map.entry("IN", new Template("GST", "GST", 1800)),          // India 18%
        Map.entry("GB", new Template("VAT", "VAT", 2000)),          // UK 20%
        Map.entry("AE", new Template("VAT", "VAT", 500)),           // UAE 5%
        Map.entry("SA", new Template("VAT", "VAT", 1500)),          // Saudi 15%
        Map.entry("SG", new Template("GST", "GST", 900)),           // Singapore 9%
        Map.entry("AU", new Template("GST", "GST", 1000)),          // Australia 10%
        Map.entry("NZ", new Template("GST", "GST", 1500)),          // New Zealand 15%
        Map.entry("JP", new Template("Consumption Tax", "VAT", 1000)), // Japan 10%
        Map.entry("CN", new Template("VAT", "VAT", 600)),           // China services 6%
        Map.entry("CA", new Template("GST", "GST", 500)),           // Canada federal 5%
        Map.entry("CH", new Template("VAT", "VAT", 810)),           // Switzerland 8.1%
        Map.entry("MY", new Template("Service Tax", "SALES_TAX", 800)), // Malaysia 8%
        Map.entry("TH", new Template("VAT", "VAT", 700)),           // Thailand 7%
        Map.entry("ID", new Template("VAT", "VAT", 1100)),          // Indonesia 11%
        Map.entry("PH", new Template("VAT", "VAT", 1200)),          // Philippines 12%
        Map.entry("VN", new Template("VAT", "VAT", 1000)),          // Vietnam 10%
        Map.entry("KR", new Template("VAT", "VAT", 1000)),          // South Korea 10%
        Map.entry("ZA", new Template("VAT", "VAT", 1500)),          // South Africa 15%
        Map.entry("BR", new Template("ISS", "SALES_TAX", 500)),     // Brazil services (typ.)
        Map.entry("MX", new Template("IVA", "VAT", 1600)),          // Mexico 16%
        Map.entry("RU", new Template("VAT", "VAT", 2000)),          // Russia 20%
        Map.entry("TR", new Template("KDV", "VAT", 2000)),          // Türkiye 20%
        Map.entry("QA", new Template("VAT", "VAT", 0)),             // Qatar — none yet
        Map.entry("KW", new Template("VAT", "VAT", 0)),             // Kuwait — none yet
        Map.entry("BH", new Template("VAT", "VAT", 1000)),          // Bahrain 10%
        Map.entry("OM", new Template("VAT", "VAT", 500)),           // Oman 5%
        Map.entry("LK", new Template("VAT", "VAT", 1800)),          // Sri Lanka 18%
        Map.entry("NP", new Template("VAT", "VAT", 1300)),          // Nepal 13%
        Map.entry("BD", new Template("VAT", "VAT", 1500)),          // Bangladesh 15%
        Map.entry("PK", new Template("Sales Tax", "SALES_TAX", 1800)), // Pakistan services (typ.)
        Map.entry("EG", new Template("VAT", "VAT", 1400)),          // Egypt 14%
        Map.entry("NG", new Template("VAT", "VAT", 750)),           // Nigeria 7.5%
        Map.entry("KE", new Template("VAT", "VAT", 1600)),          // Kenya 16%
        Map.entry("SE", new Template("Moms", "VAT", 2500)),         // Sweden 25%
        Map.entry("NO", new Template("MVA", "VAT", 2500)),          // Norway 25%
        Map.entry("DK", new Template("Moms", "VAT", 2500)),         // Denmark 25%
        Map.entry("PL", new Template("VAT", "VAT", 2300)),          // Poland 23%
        Map.entry("DE", new Template("MwSt", "VAT", 1900)),         // Germany 19%
        Map.entry("FR", new Template("TVA", "VAT", 2000)),          // France 20%
        Map.entry("IT", new Template("IVA", "VAT", 2200)),          // Italy 22%
        Map.entry("ES", new Template("IVA", "VAT", 2100)),          // Spain 21%
        Map.entry("NL", new Template("BTW", "VAT", 2100)),          // Netherlands 21%
        Map.entry("IE", new Template("VAT", "VAT", 2300)),          // Ireland 23%
        Map.entry("PT", new Template("IVA", "VAT", 2300)),          // Portugal 23%
        Map.entry("BE", new Template("BTW", "VAT", 2100)),          // Belgium 21%
        Map.entry("AT", new Template("USt", "VAT", 2000)),          // Austria 20%
        Map.entry("GR", new Template("VAT", "VAT", 2400)),          // Greece 24%
        Map.entry("FI", new Template("ALV", "VAT", 2550)),          // Finland 25.5%
        Map.entry("LU", new Template("TVA", "VAT", 1700)),          // Luxembourg 17%
        Map.entry("HK", new Template("None", "GENERIC", 0))         // Hong Kong — no VAT/GST
    );

    private CountryTaxDefaults() {}

    /** The default tax template for a country, or null when none should be seeded. */
    public static Template forCountry(String countryIso2) {
        if (countryIso2 == null) return null;
        Template t = DEFAULTS.get(countryIso2.trim().toUpperCase());
        // Zero-rate templates exist to document "no tax here" — don't seed a 0% rule.
        return (t == null || t.rateBps() <= 0) ? null : t;
    }
}
