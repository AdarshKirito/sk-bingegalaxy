package com.skbingegalaxy.payment.method;

import com.skbingegalaxy.common.enums.PaymentMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which payment methods a customer expects to see for a venue in a given country.
 *
 * <p><b>The country here is always the VENUE's, never the customer's.</b> A US
 * customer booking a Mumbai venue pays like a local Indian customer would (UPI
 * first); an Indian customer booking a New York venue sees the US rail set. This
 * mirrors how the money actually settles — the charge lands in the venue's
 * country and currency, so the rails available are the venue's rails.
 *
 * <p>This is the <em>demand</em> side of the resolution: what the market expects.
 * It is intersected with the <em>supply</em> side — what the resolved
 * {@link com.skbingegalaxy.payment.provider.PaymentProvider} can actually charge —
 * by {@link PaymentMethodResolver}. Listing a method here does not make it
 * offerable; a gateway must also support it, so we never render a button that
 * would fail at checkout.
 *
 * <p>Ordering is significant: the first entry is the country's default rail and
 * becomes the pre-selected option in the UI (UPI in India, card in the US).
 *
 * <p>{@link PaymentMethod#CASH} never appears here — it is an offline,
 * admin-recorded settlement, not something a customer can pick online.
 */
public final class PaymentMethodCatalog {

    private PaymentMethodCatalog() {}

    /** Rail sets keyed by ISO-3166-1 alpha-2. Order = local preference. */
    private static final Map<String, List<PaymentMethod>> BY_COUNTRY = buildCountryMap();

    /**
     * Countries with no explicit entry fall back to the card-first international
     * set. Card is the only rail that is near-universally available, so this is
     * the safe default for a venue in a country we have not tuned yet.
     */
    private static final List<PaymentMethod> INTERNATIONAL_DEFAULT =
        List.of(PaymentMethod.CARD, PaymentMethod.WALLET);

    private static Map<String, List<PaymentMethod>> buildCountryMap() {
        Map<String, List<PaymentMethod>> m = new LinkedHashMap<>();

        // ── India: UPI is the dominant consumer rail, then cards, then netbanking. ──
        m.put("IN", List.of(PaymentMethod.UPI, PaymentMethod.CARD,
            PaymentMethod.BANK_TRANSFER, PaymentMethod.WALLET));

        // ── North America: card-led; bank transfer is ACH and is slow, so it trails. ──
        m.put("US", List.of(PaymentMethod.CARD, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER));
        m.put("CA", List.of(PaymentMethod.CARD, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER));

        // ── UK / EEA: card-led, with SEPA/bank transfer common enough to offer. ──
        for (String eu : List.of("GB", "IE", "DE", "FR", "IT", "ES", "NL", "PT", "BE",
                                 "AT", "GR", "FI", "LU", "SE", "NO", "DK", "PL", "CH")) {
            m.put(eu, List.of(PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER, PaymentMethod.WALLET));
        }

        // ── Gulf: card-led, wallets widely used. ──
        for (String gulf : List.of("AE", "SA", "QA", "KW", "BH", "OM")) {
            m.put(gulf, List.of(PaymentMethod.CARD, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER));
        }

        // ── APAC: wallet-heavy markets. ──
        for (String apac : List.of("SG", "MY", "TH", "ID", "PH", "VN", "HK", "JP", "KR", "CN",
                                   "AU", "NZ")) {
            m.put(apac, List.of(PaymentMethod.CARD, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER));
        }

        // ── South Asia neighbours: card + bank transfer, wallets growing. ──
        for (String sa : List.of("LK", "NP", "BD", "PK")) {
            m.put(sa, List.of(PaymentMethod.CARD, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER));
        }

        return Map.copyOf(m);
    }

    /**
     * The ordered rail set a venue in {@code countryIso2} should offer, before
     * gateway capability is applied. Null/blank/unknown country → the card-first
     * international default (legacy venues predate the mandatory country field).
     */
    public static List<PaymentMethod> forCountry(String countryIso2) {
        if (countryIso2 == null || countryIso2.isBlank()) return INTERNATIONAL_DEFAULT;
        return BY_COUNTRY.getOrDefault(
            countryIso2.trim().toUpperCase(Locale.ROOT), INTERNATIONAL_DEFAULT);
    }

    /** True when we have a tuned rail set for this country (vs. falling back). */
    public static boolean isKnownCountry(String countryIso2) {
        return countryIso2 != null && !countryIso2.isBlank()
            && BY_COUNTRY.containsKey(countryIso2.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Currency → representative country, used ONLY when a venue predates the
     * mandatory country field.
     *
     * <p>Without this, a legacy Indian venue whose {@code country} column was
     * never backfilled would fall to the card-only international default and
     * silently lose UPI — a visible regression for venues that work today. The
     * venue's currency is always populated (it defaults to INR and is re-derived
     * from country on every change), so it is a reliable stand-in.
     *
     * <p>EUR maps to a representative euro-area country: every euro country
     * shares the same rail set and the SEPA label, so the choice is immaterial.
     */
    public static String inferCountryFromCurrency(String currencyIso) {
        if (currencyIso == null || currencyIso.isBlank()) return null;
        return switch (currencyIso.trim().toUpperCase(Locale.ROOT)) {
            case "INR" -> "IN";
            case "USD" -> "US";
            case "GBP" -> "GB";
            case "EUR" -> "DE";
            case "AED" -> "AE";
            case "SAR" -> "SA";
            case "SGD" -> "SG";
            case "AUD" -> "AU";
            case "CAD" -> "CA";
            case "NZD" -> "NZ";
            case "CHF" -> "CH";
            case "JPY" -> "JP";
            case "CNY" -> "CN";
            case "HKD" -> "HK";
            case "KRW" -> "KR";
            case "MYR" -> "MY";
            case "THB" -> "TH";
            case "IDR" -> "ID";
            case "PHP" -> "PH";
            case "VND" -> "VN";
            case "LKR" -> "LK";
            case "NPR" -> "NP";
            case "BDT" -> "BD";
            case "PKR" -> "PK";
            default -> null;
        };
    }

    /**
     * Customer-facing label for a rail, localised to the venue's country because
     * the same {@link PaymentMethod} is called different things in different
     * markets — BANK_TRANSFER is "Net banking" in India, "ACH transfer" in the
     * US and "SEPA transfer" in the euro area.
     */
    public static String labelFor(PaymentMethod method, String countryIso2) {
        String cc = countryIso2 == null ? "" : countryIso2.trim().toUpperCase(Locale.ROOT);
        return switch (method) {
            case UPI -> "UPI";
            case CARD -> "Credit / debit card";
            case WALLET -> switch (cc) {
                case "IN" -> "Wallet (Paytm, PhonePe, …)";
                case "CN" -> "Wallet (Alipay, WeChat Pay)";
                default -> "Digital wallet";
            };
            case BANK_TRANSFER -> switch (cc) {
                case "IN" -> "Net banking";
                case "US" -> "Bank transfer (ACH)";
                case "GB" -> "Bank transfer";
                case "DE", "FR", "IT", "ES", "NL", "IE", "PT", "BE", "AT", "GR", "FI", "LU" ->
                    "Bank transfer (SEPA)";
                default -> "Bank transfer";
            };
            case CASH -> "Cash";
        };
    }
}
