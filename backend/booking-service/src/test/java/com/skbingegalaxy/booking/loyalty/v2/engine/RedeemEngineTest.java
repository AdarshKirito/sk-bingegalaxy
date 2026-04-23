package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * through {@link PointsWalletService#debit}.
 */
class RedeemEngineTest {

    private LoyaltyConfigService configService;
    private PointsWalletService walletService;
    private LoyaltyMembershipRepository membershipRepository;
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
        tierEngine = mock(TierEngine.class);

        engine = new RedeemEngine(configService, walletService, membershipRepository, tierEngine);

        member = LoyaltyMembership.builder()
                .id(42L).tenantId(1L).programId(1L).customerId(100L)
                .currentTierCode("GOLD").memberNumber("SK-42").build();
        program = LoyaltyProgram.builder().id(1L).code("SK").build();
        binding = LoyaltyBingeBinding.builder()
                .id(500L).programId(1L).bingeId(7L)
                .status("ENABLED").legacyFrozen(false).build();

        when(membershipRepository.findById(42L)).thenReturn(Optional.of(member));
        when(configService.requireDefaultProgram()).thenReturn(program);
        when(configService.findActiveBinding(1L, 7L)).thenReturn(Optional.of(binding));
    }

    @Test
    void quote_converts_points_to_currency_at_base_rate_with_no_bonus() {
        LoyaltyBingeRedemptionRule rule = rule(100L, 0L, new BigDecimal("100.00"), null);
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now()));

        // 10 000 pts ÷ 100 ppcu = 100.00 INR
        assertThat(q.eligible()).isTrue();
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(q.pointsToBurn()).isEqualTo(10_000L);
    }

    @Test
    void quote_rejects_when_below_min_redemption_points() {
        LoyaltyBingeRedemptionRule rule = rule(100L, 5_000L, new BigDecimal("100.00"), null);
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 1_000L, new BigDecimal("500.00"), LocalDateTime.now()));

        assertThat(q.eligible()).isFalse();
        assertThat(q.rejectReason()).isEqualTo("BELOW_MIN_POINTS");
    }

    @Test
    void quote_caps_at_max_redemption_percent_of_booking() {
        LoyaltyBingeRedemptionRule rule = rule(100L, 0L, new BigDecimal("50.00"), null);  // 50 % cap
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        // Requesting 20 000 pts = INR 200, but booking is INR 100 → 50 % cap = INR 50
        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 20_000L, new BigDecimal("100.00"), LocalDateTime.now()));

        assertThat(q.eligible()).isTrue();
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(q.note()).isEqualTo("CAPPED_BY_BOOKING_PCT");
        // Points scaled down to what's actually redeemable.
        assertThat(q.pointsToBurn()).isLessThan(20_000L);
    }

    @Test
    void quote_applies_tier_bonus_from_json() {
        // Gold gets 5 % extra value.
        LoyaltyBingeRedemptionRule rule = rule(100L, 0L, new BigDecimal("100.00"),
                "{\"GOLD\":\"5\",\"PLATINUM\":\"10\"}");
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now()));

        // base = 100, + 5 % bonus = 105
        assertThat(q.currencyValue()).isEqualByComparingTo(new BigDecimal("105.00"));
        assertThat(q.tierBonusPct()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void quote_rejects_when_binding_is_frozen() {
        binding.setLegacyFrozen(true);

        var q = engine.quote(new RedeemEngine.QuoteRequest(
                42L, 7L, 10_000L, new BigDecimal("500.00"), LocalDateTime.now()));

        assertThat(q.eligible()).isFalse();
        assertThat(q.rejectReason()).isEqualTo("LEGACY_FROZEN");
    }

    @Test
    void burn_debits_wallet_and_recalcs_tier() {
        LoyaltyBingeRedemptionRule rule = rule(100L, 0L, new BigDecimal("100.00"), null);
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        LocalDateTime now = LocalDateTime.now();
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
    void burn_rejects_without_wallet_side_effects_when_not_eligible() {
        LoyaltyBingeRedemptionRule rule = rule(100L, 5_000L, new BigDecimal("100.00"), null);
        when(configService.resolveRedemptionRule(eq(500L), any())).thenReturn(Optional.of(rule));

        var res = engine.burn(new RedeemEngine.BurnRequest(
                42L, 7L, "BK-10", 1_000L, new BigDecimal("500.00"), LocalDateTime.now(), "corr"));

        assertThat(res.accepted()).isFalse();
        assertThat(res.rejectReason()).isEqualTo("BELOW_MIN_POINTS");
        verifyNoInteractions(walletService);
        verifyNoInteractions(tierEngine);
    }

    private LoyaltyBingeRedemptionRule rule(long pointsPerCurrencyUnit, long minPoints,
                                            BigDecimal maxPct, String tierBonusJson) {
        return LoyaltyBingeRedemptionRule.builder()
                .id(2000L).bindingId(500L).tenantId(1L)
                .pointsPerCurrencyUnit(pointsPerCurrencyUnit)
                .minRedemptionPoints(minPoints)
                .maxRedemptionPercent(maxPct)
                .tierBonusPctJson(tierBonusJson)
                .effectiveFrom(LocalDateTime.now().minusDays(30))
                .build();
    }
}
