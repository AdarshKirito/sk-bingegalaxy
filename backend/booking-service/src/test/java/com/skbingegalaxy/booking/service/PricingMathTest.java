package com.skbingegalaxy.booking.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden tests for the canonical pricing math (PRICE-001).
 *
 * These pins protect the exact rounding contract that every booking path
 * (create / admin-create / update / reschedule / recurring) now delegates to.
 * If one of these fails, a change has altered what customers are charged —
 * treat it as a billing regression, not a test to update casually.
 */
class PricingMathTest {

    private static PricingService.ResolvedEventPrice price(String base, String hourly, String perGuest) {
        return new PricingService.ResolvedEventPrice(
            new BigDecimal(base), new BigDecimal(hourly), new BigDecimal(perGuest), "DEFAULT", null);
    }

    // ── computeBaseAmount ───────────────────────────────────────────────

    @Test
    @DisplayName("whole hours: base + hourly×hours")
    void baseAmount_wholeHours() {
        // 1000 + 500 × 2h = 2000.00
        assertThat(PricingService.computeBaseAmount(price("1000", "500", "0"), 120))
            .isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("half hour: hourly component rounds to 2dp BEFORE the add")
    void baseAmount_halfHour() {
        // 999 + 333 × 0.5h = 999 + 166.50 → 1165.50
        assertThat(PricingService.computeBaseAmount(price("999", "333", "0"), 30))
            .isEqualByComparingTo("1165.50");
    }

    @Test
    @DisplayName("90 minutes with a rate that forces rounding")
    void baseAmount_ninetyMinutes_rounding() {
        // 100.33 × 1.5h = 150.495 → 150.50 (HALF_UP); + 0 base
        assertThat(PricingService.computeBaseAmount(price("0", "100.33", "0"), 90))
            .isEqualByComparingTo("150.50");
    }

    @Test
    @DisplayName("duration → decimal hours uses scale-4 HALF_UP division")
    void baseAmount_awkwardDuration() {
        // 50 minutes → 0.8333h; 600 × 0.8333 = 499.98 → 499.98
        assertThat(PricingService.computeBaseAmount(price("0", "600", "0"), 50))
            .isEqualByComparingTo("499.98");
    }

    // ── computeGuestAmount ──────────────────────────────────────────────

    @Test
    @DisplayName("first guest is included — 1 guest bills zero extras")
    void guestAmount_singleGuest() {
        assertThat(PricingService.computeGuestAmount(price("0", "0", "250"), 1))
            .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("extras bill at pricePerGuest each")
    void guestAmount_multipleGuests() {
        // 4 guests → 3 extras × 250 = 750
        assertThat(PricingService.computeGuestAmount(price("0", "0", "250"), 4))
            .isEqualByComparingTo("750");
    }

    @Test
    @DisplayName("zero guests never goes negative")
    void guestAmount_zeroGuests() {
        assertThat(PricingService.computeGuestAmount(price("0", "0", "250"), 0))
            .isEqualByComparingTo("0");
    }

    // ── applySurge ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null multiplier returns the input unchanged (no rounding applied)")
    void surge_nullMultiplier() {
        BigDecimal in = new BigDecimal("1234.567");
        assertThat(PricingService.applySurge(in, null)).isSameAs(in);
    }

    @Test
    @DisplayName("surge multiplies then rounds to 2dp HALF_UP")
    void surge_appliesAndRounds() {
        // 1001 × 1.25 = 1251.25
        assertThat(PricingService.applySurge(new BigDecimal("1001"), new BigDecimal("1.25")))
            .isEqualByComparingTo("1251.25");
        // 999.99 × 1.5 = 1499.985 → 1499.99 (HALF_UP)
        assertThat(PricingService.applySurge(new BigDecimal("999.99"), new BigDecimal("1.5")))
            .isEqualByComparingTo("1499.99");
    }

    @Test
    @DisplayName("full assembly parity: base + addOns + guests, then surge — the createBooking order")
    void fullAssembly_goldenTotal() {
        PricingService.ResolvedEventPrice p = price("1500", "800", "200");
        BigDecimal base = PricingService.computeBaseAmount(p, 150);           // 1500 + 800×2.5 = 3500.00
        BigDecimal addOns = new BigDecimal("450");                            // resolved line prices
        BigDecimal guests = PricingService.computeGuestAmount(p, 3);          // 2 × 200 = 400
        BigDecimal total = PricingService.applySurge(
            base.add(addOns).add(guests), new BigDecimal("1.2"));             // 4350 × 1.2
        assertThat(total).isEqualByComparingTo("5220.00");
    }
}
