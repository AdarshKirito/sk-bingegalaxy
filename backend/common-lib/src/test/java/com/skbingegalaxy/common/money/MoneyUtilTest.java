package com.skbingegalaxy.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production-grade tests for ISO-4217 minor-unit rounding and basis-point
 * tax math. These guarantees underpin every snapshot, invoice, ledger entry
 * and credit note in the system, so any regression here is a financial bug.
 */
class MoneyUtilTest {

    @Test
    void decimalDigits_isoMinorUnits() {
        assertEquals(2, MoneyUtil.decimalDigits("USD"));
        assertEquals(2, MoneyUtil.decimalDigits("INR"));
        assertEquals(2, MoneyUtil.decimalDigits("EUR"));
        assertEquals(2, MoneyUtil.decimalDigits("GBP"));
        assertEquals(2, MoneyUtil.decimalDigits(" usd "));
        assertEquals(0, MoneyUtil.decimalDigits("JPY"));
        assertEquals(2, MoneyUtil.decimalDigits(null));
        assertEquals(2, MoneyUtil.decimalDigits(" "));
        assertEquals(2, MoneyUtil.decimalDigits("XXX"));
        // Unknown currency falls back to a safe default.
        assertEquals(2, MoneyUtil.decimalDigits("XYZ"));
    }

    @Test
    void round_respectsCurrencyMinorUnits() {
        // INR has 2 decimals, JPY has 0 decimals.
        assertEquals(new BigDecimal("123.46"),
                MoneyUtil.round(new BigDecimal("123.456"), "INR"));
        assertEquals(new BigDecimal("124"),
                MoneyUtil.round(new BigDecimal("123.6"), "JPY"));
        assertEquals(new BigDecimal("123.457"),
                MoneyUtil.round(new BigDecimal("123.4567"), 3));
        assertEquals(BigDecimal.ZERO, MoneyUtil.round(null, "INR"));
        assertEquals(BigDecimal.ZERO, MoneyUtil.round(null, 2));
    }

    @Test
    void zeroPredicatesAndArithmetic_areNullSafe() {
        assertTrue(MoneyUtil.isZeroOrNull(null));
        assertTrue(MoneyUtil.isZeroOrNull(new BigDecimal("0.00")));
        assertFalse(MoneyUtil.isZeroOrNull(new BigDecimal("0.01")));

        assertEquals(new BigDecimal("7"), MoneyUtil.add(new BigDecimal("5"), new BigDecimal("2")));
        assertEquals(new BigDecimal("5"), MoneyUtil.add(new BigDecimal("5"), null));
        assertEquals(new BigDecimal("3"), MoneyUtil.sub(new BigDecimal("5"), new BigDecimal("2")));
        assertEquals(new BigDecimal("-2"), MoneyUtil.sub(null, new BigDecimal("2")));
    }

    @Test
    void div_returnsZeroForUnsafeDivisors() {
        assertEquals(BigDecimal.ZERO, MoneyUtil.div(new BigDecimal("10"), null));
        assertEquals(BigDecimal.ZERO, MoneyUtil.div(new BigDecimal("10"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("2.50000000"), MoneyUtil.div(new BigDecimal("10"), new BigDecimal("4")));
    }

    @Test
    void nonNegative_clampsNullAndNegativeValues() {
        assertEquals(BigDecimal.ZERO, MoneyUtil.nonNegative(null));
        assertEquals(BigDecimal.ZERO, MoneyUtil.nonNegative(new BigDecimal("-0.01")));
        assertEquals(new BigDecimal("0.01"), MoneyUtil.nonNegative(new BigDecimal("0.01")));
    }

    @Test
    void applyBps_18percentGst() {
        // 1000 INR * 18% = 180.00
        BigDecimal tax = MoneyUtil.applyBps(new BigDecimal("1000.00"), 1800);
        assertEquals(0, tax.compareTo(new BigDecimal("180.00")),
                "18% (1800 bps) of 1000 must equal 180");
        assertEquals(BigDecimal.ZERO, MoneyUtil.applyBps(null, 1800));
        assertEquals(BigDecimal.ZERO, MoneyUtil.applyBps(new BigDecimal("1000.00"), 0));
    }

    @Test
    void extractInclusiveTax_inverseOfApplyBps() {
        // 1180 inclusive of 18% GST -> tax portion is ~180 (callers round
        // to currency minor units before persistence).
        BigDecimal tax = MoneyUtil.round(
                MoneyUtil.extractInclusiveTax(new BigDecimal("1180.00"), 1800),
                "INR");
        assertEquals(0, tax.compareTo(new BigDecimal("180.00")),
                "Inclusive tax extraction must round-trip with applyBps after currency rounding");
        assertEquals(BigDecimal.ZERO, MoneyUtil.extractInclusiveTax(null, 1800));
        assertEquals(BigDecimal.ZERO, MoneyUtil.extractInclusiveTax(new BigDecimal("1180.00"), 0));
    }

    @Test
    void convertWithRate_roundsToTargetCurrency() {
        // 1000 INR at rate 0.012 USD/INR = 12.00 USD
        BigDecimal usd = MoneyUtil.convertWithRate(
                new BigDecimal("1000.00"), new BigDecimal("0.012"), "USD");
        assertEquals(0, usd.compareTo(new BigDecimal("12.00")));
    }

    @Test
    void zeroIfNull_returnsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(MoneyUtil.zeroIfNull(null)));
        assertEquals(0, new BigDecimal("5").compareTo(MoneyUtil.zeroIfNull(new BigDecimal("5"))));
    }
}
