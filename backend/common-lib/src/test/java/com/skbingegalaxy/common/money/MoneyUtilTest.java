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
        assertEquals(0, MoneyUtil.decimalDigits("JPY"));
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
    }

    @Test
    void applyBps_18percentGst() {
        // 1000 INR * 18% = 180.00
        BigDecimal tax = MoneyUtil.applyBps(new BigDecimal("1000.00"), 1800);
        assertEquals(0, tax.compareTo(new BigDecimal("180.00")),
                "18% (1800 bps) of 1000 must equal 180");
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
