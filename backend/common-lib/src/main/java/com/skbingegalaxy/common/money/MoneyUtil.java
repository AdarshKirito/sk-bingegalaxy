package com.skbingegalaxy.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Money helpers for the SK Binge Galaxy financial pipeline.
 *
 * <p>All monetary computations across the platform must go through this
 * class so that:
 *
 * <ul>
 *   <li>{@link BigDecimal} is used end-to-end (no float/double drift).</li>
 *   <li>Final rounding always uses {@link RoundingMode#HALF_UP} — the same
 *       rule used for invoicing in most jurisdictions including India and
 *       the EU.</li>
 *   <li>Currency-specific minor units (decimal digits) are honoured. For
 *       example JPY rounds to 0 decimals while USD rounds to 2.</li>
 * </ul>
 *
 * <p>The class is intentionally <strong>stateless</strong> and
 * <strong>final</strong>: it is safe to call from any service or test.
 */
public final class MoneyUtil {

    /** Internal computation scale before final rounding to currency minor units. */
    public static final int CALC_SCALE = 8;

    /** Default rounding mode for money. */
    public static final RoundingMode ROUND = RoundingMode.HALF_UP;

    private MoneyUtil() { /* utility */ }

    // ── Currency metadata ───────────────────────────────────────────────

    /**
     * Returns the number of decimal digits (minor units) for an ISO-4217
     * currency code. Falls back to 2 when the JVM cannot resolve the
     * currency (e.g. unusual codes such as "XBT").
     */
    public static int decimalDigits(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) return 2;
        try {
            Currency c = Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
            int d = c.getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (IllegalArgumentException ex) {
            return 2;
        }
    }

    /** True when the input is null or numerically zero (any scale). */
    public static boolean isZeroOrNull(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    /** Null-safe wrapper that returns {@link BigDecimal#ZERO} for nulls. */
    public static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ── Rounding ────────────────────────────────────────────────────────

    /** Round to the currency's minor units using HALF_UP. */
    public static BigDecimal round(BigDecimal v, String currencyCode) {
        if (v == null) return BigDecimal.ZERO;
        return v.setScale(decimalDigits(currencyCode), ROUND);
    }

    /** Round to the given explicit number of decimal places. */
    public static BigDecimal round(BigDecimal v, int decimals) {
        if (v == null) return BigDecimal.ZERO;
        return v.setScale(decimals, ROUND);
    }

    // ── Arithmetic ──────────────────────────────────────────────────────

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return zeroIfNull(a).add(zeroIfNull(b));
    }

    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return zeroIfNull(a).subtract(zeroIfNull(b));
    }

    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return zeroIfNull(a).multiply(zeroIfNull(b)).setScale(CALC_SCALE, ROUND);
    }

    /**
     * Safe division used for FX and ratio operations.
     * Returns ZERO when the divisor is null or zero.
     */
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) return BigDecimal.ZERO;
        return zeroIfNull(a).divide(b, CALC_SCALE, ROUND);
    }

    /** Returns max(0, value). Useful when a discount cannot push a total below zero. */
    public static BigDecimal nonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    // ── FX conversion ───────────────────────────────────────────────────

    /**
     * Convert {@code base} from base currency into {@code targetCurrency} using
     * the supplied {@code rate}. The semantics follow our pricing convention:
     * {@code converted = base * rate}. Result is rounded to the target
     * currency's minor units.
     */
    public static BigDecimal convertWithRate(BigDecimal base, BigDecimal rate, String targetCurrency) {
        BigDecimal converted = mul(base, rate);
        return round(converted, targetCurrency);
    }

    // ── Basis-point math (for tax rules) ────────────────────────────────

    private static final BigDecimal BPS_DIVISOR = new BigDecimal("10000");

    /** Apply a basis-point rate (e.g. 1800 bps = 18%) to a value. */
    public static BigDecimal applyBps(BigDecimal value, int bps) {
        if (value == null || bps == 0) return BigDecimal.ZERO;
        BigDecimal rate = BigDecimal.valueOf(bps).divide(BPS_DIVISOR, CALC_SCALE, ROUND);
        return mul(value, rate);
    }

    /**
     * Extract the inclusive tax component embedded in {@code grossInclusive}
     * given a basis-point rate. Formula: {@code tax = gross * (bps / (10000 + bps))}.
     */
    public static BigDecimal extractInclusiveTax(BigDecimal grossInclusive, int bps) {
        if (grossInclusive == null || bps == 0) return BigDecimal.ZERO;
        BigDecimal denom = BPS_DIVISOR.add(BigDecimal.valueOf(bps));
        BigDecimal numer = BigDecimal.valueOf(bps);
        BigDecimal factor = numer.divide(denom, CALC_SCALE, ROUND);
        return mul(grossInclusive, factor);
    }
}
