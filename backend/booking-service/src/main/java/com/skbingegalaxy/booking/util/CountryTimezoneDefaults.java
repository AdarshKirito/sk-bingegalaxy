package com.skbingegalaxy.booking.util;

import java.util.Locale;
import java.util.Map;

/**
 * Derives a sensible default IANA timezone for a venue from its structured
 * address (country → state/province → major-city overrides), mirroring the
 * {@code CountryCurrency} / {@code CountryTaxDefaults} pattern.
 *
 * <p>Used when an admin creates a venue without explicitly picking a zone —
 * the venue's city decides the default, NEVER the server JVM zone or the
 * admin's browser zone. The admin still sees (and pre-approval may change)
 * the derived value; after super-admin approval the zone is locked behind the
 * TIMEZONE_CHANGE authority grant.</p>
 *
 * <p>Single-zone countries map directly. Multi-zone countries (US, CA, AU,
 * BR, MX, RU, ID) consult a state/province table and fall back to the
 * country's dominant business zone. This is a curated default, not a
 * geodetic lookup — the super-admin confirms the zone at approval time.</p>
 */
public final class CountryTimezoneDefaults {

    private CountryTimezoneDefaults() {}

    /** ISO-3166-1 alpha-2 → IANA zone for countries with one business zone. */
    private static final Map<String, String> COUNTRY_ZONE = Map.ofEntries(
        Map.entry("IN", "Asia/Kolkata"),
        Map.entry("LK", "Asia/Colombo"),
        Map.entry("NP", "Asia/Kathmandu"),
        Map.entry("BD", "Asia/Dhaka"),
        Map.entry("PK", "Asia/Karachi"),
        Map.entry("AE", "Asia/Dubai"),
        Map.entry("SA", "Asia/Riyadh"),
        Map.entry("QA", "Asia/Qatar"),
        Map.entry("KW", "Asia/Kuwait"),
        Map.entry("BH", "Asia/Bahrain"),
        Map.entry("OM", "Asia/Muscat"),
        Map.entry("IL", "Asia/Jerusalem"),
        Map.entry("TR", "Europe/Istanbul"),
        Map.entry("SG", "Asia/Singapore"),
        Map.entry("MY", "Asia/Kuala_Lumpur"),
        Map.entry("TH", "Asia/Bangkok"),
        Map.entry("VN", "Asia/Ho_Chi_Minh"),
        Map.entry("PH", "Asia/Manila"),
        Map.entry("HK", "Asia/Hong_Kong"),
        Map.entry("TW", "Asia/Taipei"),
        Map.entry("CN", "Asia/Shanghai"),
        Map.entry("JP", "Asia/Tokyo"),
        Map.entry("KR", "Asia/Seoul"),
        Map.entry("GB", "Europe/London"),
        Map.entry("IE", "Europe/Dublin"),
        Map.entry("FR", "Europe/Paris"),
        Map.entry("DE", "Europe/Berlin"),
        Map.entry("NL", "Europe/Amsterdam"),
        Map.entry("BE", "Europe/Brussels"),
        Map.entry("LU", "Europe/Luxembourg"),
        Map.entry("CH", "Europe/Zurich"),
        Map.entry("AT", "Europe/Vienna"),
        Map.entry("IT", "Europe/Rome"),
        Map.entry("ES", "Europe/Madrid"),
        Map.entry("PT", "Europe/Lisbon"),
        Map.entry("PL", "Europe/Warsaw"),
        Map.entry("CZ", "Europe/Prague"),
        Map.entry("HU", "Europe/Budapest"),
        Map.entry("RO", "Europe/Bucharest"),
        Map.entry("GR", "Europe/Athens"),
        Map.entry("SE", "Europe/Stockholm"),
        Map.entry("NO", "Europe/Oslo"),
        Map.entry("DK", "Europe/Copenhagen"),
        Map.entry("FI", "Europe/Helsinki"),
        Map.entry("UA", "Europe/Kyiv"),
        Map.entry("EG", "Africa/Cairo"),
        Map.entry("MA", "Africa/Casablanca"),
        Map.entry("NG", "Africa/Lagos"),
        Map.entry("GH", "Africa/Accra"),
        Map.entry("KE", "Africa/Nairobi"),
        Map.entry("TZ", "Africa/Dar_es_Salaam"),
        Map.entry("ZA", "Africa/Johannesburg"),
        Map.entry("AR", "America/Argentina/Buenos_Aires"),
        Map.entry("CL", "America/Santiago"),
        Map.entry("CO", "America/Bogota"),
        Map.entry("PE", "America/Lima"),
        Map.entry("NZ", "Pacific/Auckland")
    );

    /** Dominant business zone for multi-zone countries when no state matches. */
    private static final Map<String, String> MULTI_ZONE_COUNTRY_FALLBACK = Map.of(
        "US", "America/New_York",
        "CA", "America/Toronto",
        "AU", "Australia/Sydney",
        "BR", "America/Sao_Paulo",
        "MX", "America/Mexico_City",
        "RU", "Europe/Moscow",
        "ID", "Asia/Jakarta"
    );

    /**
     * "CC|STATE" → zone for multi-zone countries. State keys accept both the
     * two-letter code and the common full name (normalized to upper-case with
     * spaces collapsed). Split-zone US states map to their dominant zone.
     */
    private static final Map<String, String> STATE_ZONE = Map.<String, String>ofEntries(
        // United States
        Map.entry("US|CT", "America/New_York"), Map.entry("US|DE", "America/New_York"),
        Map.entry("US|DC", "America/New_York"), Map.entry("US|FL", "America/New_York"),
        Map.entry("US|GA", "America/New_York"), Map.entry("US|ME", "America/New_York"),
        Map.entry("US|MD", "America/New_York"), Map.entry("US|MA", "America/New_York"),
        Map.entry("US|MI", "America/Detroit"),  Map.entry("US|NH", "America/New_York"),
        Map.entry("US|NJ", "America/New_York"), Map.entry("US|NY", "America/New_York"),
        Map.entry("US|NC", "America/New_York"), Map.entry("US|OH", "America/New_York"),
        Map.entry("US|PA", "America/New_York"), Map.entry("US|RI", "America/New_York"),
        Map.entry("US|SC", "America/New_York"), Map.entry("US|VT", "America/New_York"),
        Map.entry("US|VA", "America/New_York"), Map.entry("US|WV", "America/New_York"),
        Map.entry("US|IN", "America/Indiana/Indianapolis"),
        Map.entry("US|KY", "America/New_York"), Map.entry("US|TN", "America/Chicago"),
        Map.entry("US|AL", "America/Chicago"),  Map.entry("US|AR", "America/Chicago"),
        Map.entry("US|IL", "America/Chicago"),  Map.entry("US|IA", "America/Chicago"),
        Map.entry("US|KS", "America/Chicago"),  Map.entry("US|LA", "America/Chicago"),
        Map.entry("US|MN", "America/Chicago"),  Map.entry("US|MS", "America/Chicago"),
        Map.entry("US|MO", "America/Chicago"),  Map.entry("US|NE", "America/Chicago"),
        Map.entry("US|ND", "America/Chicago"),  Map.entry("US|OK", "America/Chicago"),
        Map.entry("US|SD", "America/Chicago"),  Map.entry("US|TX", "America/Chicago"),
        Map.entry("US|WI", "America/Chicago"),
        Map.entry("US|AZ", "America/Phoenix"),  Map.entry("US|CO", "America/Denver"),
        Map.entry("US|ID", "America/Boise"),    Map.entry("US|MT", "America/Denver"),
        Map.entry("US|NM", "America/Denver"),   Map.entry("US|UT", "America/Denver"),
        Map.entry("US|WY", "America/Denver"),
        Map.entry("US|CA", "America/Los_Angeles"), Map.entry("US|NV", "America/Los_Angeles"),
        Map.entry("US|OR", "America/Los_Angeles"), Map.entry("US|WA", "America/Los_Angeles"),
        Map.entry("US|AK", "America/Anchorage"),   Map.entry("US|HI", "Pacific/Honolulu"),
        // Canada
        Map.entry("CA|ON", "America/Toronto"),   Map.entry("CA|QC", "America/Toronto"),
        Map.entry("CA|NS", "America/Halifax"),   Map.entry("CA|NB", "America/Halifax"),
        Map.entry("CA|PE", "America/Halifax"),   Map.entry("CA|NL", "America/St_Johns"),
        Map.entry("CA|MB", "America/Winnipeg"),  Map.entry("CA|SK", "America/Regina"),
        Map.entry("CA|AB", "America/Edmonton"),  Map.entry("CA|BC", "America/Vancouver"),
        // Australia
        Map.entry("AU|NSW", "Australia/Sydney"),   Map.entry("AU|VIC", "Australia/Melbourne"),
        Map.entry("AU|QLD", "Australia/Brisbane"), Map.entry("AU|SA", "Australia/Adelaide"),
        Map.entry("AU|WA", "Australia/Perth"),     Map.entry("AU|TAS", "Australia/Hobart"),
        Map.entry("AU|NT", "Australia/Darwin"),    Map.entry("AU|ACT", "Australia/Sydney"),
        // Brazil (dominant zones)
        Map.entry("BR|SP", "America/Sao_Paulo"), Map.entry("BR|RJ", "America/Sao_Paulo"),
        Map.entry("BR|AM", "America/Manaus"),
        // Indonesia
        Map.entry("ID|JK", "Asia/Jakarta"),  Map.entry("ID|BA", "Asia/Makassar"),
        Map.entry("ID|PA", "Asia/Jayapura")
    );

    /** Full state names → the codes used in {@link #STATE_ZONE}. */
    private static final Map<String, String> STATE_NAME_TO_CODE = Map.<String, String>ofEntries(
        Map.entry("US|NEW YORK", "NY"), Map.entry("US|CALIFORNIA", "CA"),
        Map.entry("US|TEXAS", "TX"), Map.entry("US|FLORIDA", "FL"),
        Map.entry("US|ILLINOIS", "IL"), Map.entry("US|WASHINGTON", "WA"),
        Map.entry("US|NEVADA", "NV"), Map.entry("US|ARIZONA", "AZ"),
        Map.entry("US|COLORADO", "CO"), Map.entry("US|GEORGIA", "GA"),
        Map.entry("US|MASSACHUSETTS", "MA"), Map.entry("US|NEW JERSEY", "NJ"),
        Map.entry("US|PENNSYLVANIA", "PA"), Map.entry("US|MICHIGAN", "MI"),
        Map.entry("US|OHIO", "OH"), Map.entry("US|OREGON", "OR"),
        Map.entry("CA|ONTARIO", "ON"), Map.entry("CA|QUEBEC", "QC"),
        Map.entry("CA|BRITISH COLUMBIA", "BC"), Map.entry("CA|ALBERTA", "AB"),
        Map.entry("CA|MANITOBA", "MB"), Map.entry("CA|SASKATCHEWAN", "SK"),
        Map.entry("CA|NOVA SCOTIA", "NS"),
        Map.entry("AU|NEW SOUTH WALES", "NSW"), Map.entry("AU|VICTORIA", "VIC"),
        Map.entry("AU|QUEENSLAND", "QLD"), Map.entry("AU|SOUTH AUSTRALIA", "SA"),
        Map.entry("AU|WESTERN AUSTRALIA", "WA"), Map.entry("AU|TASMANIA", "TAS"),
        Map.entry("AU|NORTHERN TERRITORY", "NT")
    );

    /**
     * Best-effort zone for a structured address. Returns {@code null} when the
     * country is unknown — callers fall back to the platform default zone.
     */
    public static String forLocation(String country, String state, String city) {
        if (country == null || country.isBlank()) return null;
        String cc = country.trim().toUpperCase(Locale.ROOT);

        if (MULTI_ZONE_COUNTRY_FALLBACK.containsKey(cc)) {
            String st = normalize(state);
            if (st != null) {
                String code = STATE_NAME_TO_CODE.getOrDefault(cc + "|" + st, st);
                String zone = STATE_ZONE.get(cc + "|" + code);
                if (zone != null) return zone;
            }
            return MULTI_ZONE_COUNTRY_FALLBACK.get(cc);
        }
        return COUNTRY_ZONE.get(cc);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
