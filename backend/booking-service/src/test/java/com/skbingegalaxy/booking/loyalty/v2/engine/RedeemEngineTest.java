package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService.EffectiveRedemptionTerms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedeemEngine}.
 *
 * <p>Exercises the quote pipeline — min-points guard, max-percent cap,
 * tier bonus application — and the burn path's idempotent write
 * through {@link PointsWalletService#debit}. The engine now consumes the
 * resolved {@link EffectiveRedemptionTerms} (per-binge override → venue-country
 * config → program default), so these tests stub that resolver directly.
 */
class RedeemEngineTest {

    private LoyaltyConfigService configService;
    private PointsWalletService walletService;
    private LoyaltyMembershipRepository membershipRepository;
    private LoyaltyPointsWalletRepository walletRepository;
    private TierEngine tierEngine;

    private RedeemEngine engine;

    private LoyaltyMembership member;
    private LoyaltyProgram program;
    private LoyaltyBingeBinding binding;

    @BeforeEach
    void setup() {
        configService = mock(LoyaltyConfigService.class);
        walletService = mock(PointsWalletService.class);
        membershipRepository = mock(LoyaltyMembershipRepository.class);
        walletRepository = mock(LoyaltyPointsWalletRepository.class);
        tierEngine = mock(TierEngine.class);

        engine = new RedeemEngine(configService, walletService, membershipRepository, walletRepository, tierEngine);

        member = LoyaltyMembership.builder()
                .id(42L).tenantId(1L).programId(1L).customerId(100L)
                .currentTierCode("GOLD").memberNumber("SK-42").build();
        program = LoyaltyProgram.builder().id(1L).code("SK").build();
        binding = LoyaltyBingeBinding.builder()
                .id(500L).programId(1L).bingeId(7L)
                .status("ENABLED").legacyFrozen(false).build();

        when(membershipRepository.findById(42L)).thenReturn(Optional.of(member));
        when(walletRepository.findByMembershipId(42L)).thenReturn(Optional.of(
                LoyaltyPointsWallet.builder().id(1000L).membershipId(42L).currentBalance(100_000L).build()));
        when(configService.requireDefaultProgram()).thenReturn(program);
        when(configService.findActiveBinding(1L, 7L)).thenReturn(Optional.of(binding));
    }

    @Test
    void quote_converts_points_to_currency_at_base_rate_with_no_bonus() {
        stubTerms(terms(100L, 0L, new BigDecimal("100.00"), null, "BINGE_RULE"));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        // 10 000 pts ÷ 100 ppcu = 100.00 INR
        assertThat(q.eligible()).isTrue();
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(q.pointsToBurn()).isEqualTo(10_000L);
    }

    @Test
    void quote_uses_country_fallback_terms_when_no_binge_override() {
        // No per-binge rule → resolver returns the venue's COUNTRY_CONFIG value.
        stubTerms(terms(100L, 0L, new BigDecimal("100.00"), null, "COUNTRY_CONFIG"));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(q.eligible()).isTrue();
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void quote_rejects_when_below_min_redemption_points() {
        stubTerms(terms(100L, 5_000L, new BigDecimal("100.00"), null, "BINGE_RULE"));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 1_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(q.eligible()).isFalse();
        assertThat(q.rejectReason()).isEqualTo("BELOW_MIN_POINTS");
    }

    @Test
    void quote_caps_at_max_redemption_percent_of_booking() {
        stubTerms(terms(100L, 0L, new BigDecimal("50.00"), null, "BINGE_RULE"));  // 50 % cap

        // Requesting 20 000 pts = INR 200, but booking is INR 100 → 50 % cap = INR 50
        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 20_000L, new BigDecimal("100.00"), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(q.eligible()).isTrue();
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(q.note()).isEqualTo("CAPPED_BY_BOOKING_PCT");
        // Points scaled down to what's actually redeemable.
        assertThat(q.pointsToBurn()).isLessThan(20_000L);
    }

    @Test
    void quote_rejects_when_wallet_balance_cannot_cover_effective_points() {
        stubTerms(terms(100L, 0L, new BigDecimal("100.00"), null, "BINGE_RULE"));
        when(walletRepository.findByMembershipId(42L)).thenReturn(Optional.of(
                LoyaltyPointsWallet.builder().id(1000L).membershipId(42L).currentBalance(999L).build()));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 1_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(q.eligible()).isFalse();
        assertThat(q.rejectReason()).isEqualTo("INSUFFICIENT_POINTS");
    }

    @Test
    void quote_applies_tier_bonus_from_json() {
        // Gold gets 5 % extra value.
        stubTerms(terms(100L, 0L, new BigDecimal("100.00"),
                "{\"GOLD\":\"5\",\"PLATINUM\":\"10\"}", "BINGE_RULE"));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        // base = 100, + 5 % bonus = 105
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("105.00"));
        assertThat(q.tierBonusPct()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void quote_rejects_when_binding_is_frozen() {
        binding.setLegacyFrozen(true);

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(q.eligible()).isFalse();
        assertThat(q.rejectReason()).isEqualTo("LEGACY_FROZEN");
    }

    @Test
    void maxRedeemable_reports_ceiling_and_per_point_value() {
        stubTerms(terms(100L, 0L, new BigDecimal("50.00"), null, "COUNTRY_CONFIG"));
        // Balance 100k pts, booking 100 → 50% cap = 50 currency = 5,000 pts.
        var max = engine.maxRedeemable(42L, 7L, new BigDecimal("100.00"), LocalDateTime.now(ZoneOffset.UTC));

        assertThat(max.eligible()).isTrue();
        assertThat(max.maxPoints()).isEqualTo(5_000L);
        assertThat(max.maxDiscount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(max.pointsPerCurrencyUnit()).isEqualTo(100L);
    }

    @Test
    void burn_debits_wallet_and_recalcs_tier() {
        stubTerms(terms(100L, 0L, new BigDecimal("100.00"), null, "BINGE_RULE"));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var res = engine.burn(new RedeemEngine.BurnRequest(
                42L, 7L, "BK-9", 10_000L, new BigDecimal("500.00"), now, "corr"));

        assertThat(res.accepted()).isTrue();
        assertThat(res.pointsBurned()).isEqualTo(10_000L);
        assertThat(res.currencyApplied()).isEqualByComparingTo(new BigDecimal("100.00"));

        ArgumentCaptor<PointsWalletService.DebitRequest> cap =
                ArgumentCaptor.forClass(PointsWalletService.DebitRequest.class);
        verify(walletService).debit(cap.capture());
        assertThat(cap.getValue().membershipId()).isEqualTo(42L);
        assertThat(cap.getValue().bookingRef()).isEqualTo("BK-9");
        assertThat(cap.getValue().idempotencyKey()).contains("BK-9").contains("10000");

        verify(tierEngine).recalculateTier(42L, now);
    }

    @Test
    void burn_debits_only_the_capped_quote_points() {
        stubTerms(terms(100L, 0L, new BigDecimal("50.00"), null, "BINGE_RULE"));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var res = engine.burn(new RedeemEngine.BurnRequest(
                42L, 7L, "BK-CAP", 20_000L, new BigDecimal("100.00"), now, "corr"));

        assertThat(res.accepted()).isTrue();
        assertThat(res.currencyApplied()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(res.pointsBurned()).isEqualTo(5_000L);

        ArgumentCaptor<PointsWalletService.DebitRequest> cap =
                ArgumentCaptor.forClass(PointsWalletService.DebitRequest.class);
        verify(walletService).debit(cap.capture());
        assertThat(cap.getValue().points()).isEqualTo(5_000L);
        assertThat(cap.getValue().idempotencyKey()).contains("BK-CAP").contains("5000");
    }

    @Test
    void burn_rejects_without_wallet_side_effects_when_not_eligible() {
        stubTerms(terms(100L, 5_000L, new BigDecimal("100.00"), null, "BINGE_RULE"));

        var res = engine.burn(new RedeemEngine.BurnRequest(
                42L, 7L, "BK-10", 1_000L, new BigDecimal("500.00"), LocalDateTime.now(ZoneOffset.UTC), "corr"));

        assertThat(res.accepted()).isFalse();
        assertThat(res.rejectReason()).isEqualTo("BELOW_MIN_POINTS");
        verifyNoInteractions(walletService);
        verifyNoInteractions(tierEngine);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void stubTerms(EffectiveRedemptionTerms t) {
        when(configService.resolveEffectiveRedemption(eq(500L), eq(7L), any())).thenReturn(t);
    }

    private EffectiveRedemptionTerms terms(long pointsPerCurrencyUnit, long minPoints,
                                           BigDecimal maxPct, String tierBonusJson, String source) {
        return new EffectiveRedemptionTerms(
                pointsPerCurrencyUnit, minPoints, maxPct, tierBonusJson, source,
                "COUNTRY_CONFIG".equals(source) ? "IN" : null);
    }
}
