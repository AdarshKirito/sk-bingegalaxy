package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EarnEngine}.
 *
 * <p>Cover the economic contract (raw = floor(amount × num / den), then
 * × tierMultiplier, then capped by capPerBooking) and the skip paths
 * that keep v2 silent when it must not earn (missing binding,
 * legacy-frozen binge, no active rule, below-min).
 */
class EarnEngineTest {

    private LoyaltyConfigService configService;
    private PointsWalletService walletService;
    private LoyaltyMembershipRepository membershipRepository;
    private LoyaltyQualificationEventRepository qcEventRepository;
    private TierEngine tierEngine;

    private EarnEngine engine;

    private LoyaltyMembership member;
    private LoyaltyProgram program;
    private LoyaltyBingeBinding binding;

    @BeforeEach
    void setup() {
        configService = mock(LoyaltyConfigService.class);
        walletService = mock(PointsWalletService.class);
        membershipRepository = mock(LoyaltyMembershipRepository.class);
        qcEventRepository = mock(LoyaltyQualificationEventRepository.class);
        tierEngine = mock(TierEngine.class);

        engine = new EarnEngine(configService, walletService, membershipRepository,
                qcEventRepository, tierEngine);

        member = LoyaltyMembership.builder()
                .id(42L).tenantId(1L).programId(1L).customerId(100L)
                .memberNumber("SK-TEST-0001")
                .currentTierCode("GOLD")
                .build();

        program = LoyaltyProgram.builder()
                .id(1L).code("SK_MEMBERSHIP").pointsExpiryDays(540).build();

        binding = LoyaltyBingeBinding.builder()
                .id(500L).programId(1L).bingeId(7L)
                .status("ENABLED").legacyFrozen(false).build();

        when(membershipRepository.findById(42L)).thenReturn(Optional.of(member));
        when(configService.requireDefaultProgram()).thenReturn(program);
        when(configService.findActiveBinding(1L, 7L)).thenReturn(Optional.of(binding));
        when(qcEventRepository.findByMembershipIdAndBookingRef(anyLong(), anyString()))
                .thenReturn(java.util.List.of());
        when(qcEventRepository.save(any())).thenAnswer(inv -> {
            LoyaltyQualificationEvent e = inv.getArgument(0);
            e.setId(999L);
            return e;
        });
        when(walletService.credit(any())).thenReturn(LoyaltyLedgerEntry.builder().id(7L).build());
    }

    @Test
    void awards_points_with_tier_multiplier_and_writes_qc_event() {
        LoyaltyBingeEarningRule rule = rule(10L, "1.00",
                new BigDecimal("1.50"), BigDecimal.ONE, null, null);
        when(configService.resolveEarningRule(eq(500L), eq("GOLD"), any())).thenReturn(Optional.of(rule));

        LocalDateTime now = LocalDateTime.of(2026, 5, 10, 14, 0);
        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-1", new BigDecimal("1000.00"), now, "corr-1"));

        // raw = floor(1000 × 10 / 1) = 10000; tier 1.5× = 15000; no cap
        assertThat(result.awarded()).isTrue();
        assertThat(result.pointsAwarded()).isEqualTo(15_000L);
        assertThat(result.qualifyingCreditsAwarded()).isEqualTo(10_000L);

        ArgumentCaptor<PointsWalletService.CreditRequest> cap =
                ArgumentCaptor.forClass(PointsWalletService.CreditRequest.class);
        verify(walletService).credit(cap.capture());
        assertThat(cap.getValue().bookingRef()).isEqualTo("BK-1");
        assertThat(cap.getValue().points()).isEqualTo(15_000L);
        assertThat(cap.getValue().expiresAt()).isEqualTo(now.plusDays(540));

        verify(tierEngine).recalculateTier(42L, now);
    }

    @Test
    void caps_points_at_cap_per_booking() {
        LoyaltyBingeEarningRule rule = rule(10L, "1.00",
                BigDecimal.ONE, BigDecimal.ONE, null, 500L);
        when(configService.resolveEarningRule(eq(500L), eq("GOLD"), any())).thenReturn(Optional.of(rule));

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-2", new BigDecimal("1000.00"),
                LocalDateTime.of(2026, 5, 10, 14, 0), "corr"));

        assertThat(result.pointsAwarded()).isEqualTo(500L);
        assertThat(result.qualifyingCreditsAwarded()).isEqualTo(10_000L);
    }

    @Test
    void skips_when_no_active_binding() {
        when(configService.findActiveBinding(1L, 7L)).thenReturn(Optional.empty());

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-3", new BigDecimal("500.00"), LocalDateTime.now(), "c"));

        assertThat(result.awarded()).isFalse();
        assertThat(result.skipReason()).isEqualTo("NO_BINDING");
        verifyNoInteractions(walletService);
    }

    @Test
    void skips_when_binding_is_legacy_frozen() {
        binding.setLegacyFrozen(true);

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-4", new BigDecimal("500.00"), LocalDateTime.now(), "c"));

        assertThat(result.skipReason()).isEqualTo("LEGACY_FROZEN");
        verifyNoInteractions(walletService);
    }

    @Test
    void skips_when_no_active_earn_rule() {
        when(configService.resolveEarningRule(eq(500L), eq("GOLD"), any())).thenReturn(Optional.empty());

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-5", new BigDecimal("500.00"), LocalDateTime.now(), "c"));

        assertThat(result.skipReason()).isEqualTo("NO_EARN_RULE");
        verifyNoInteractions(walletService);
    }

    @Test
    void skips_when_booking_below_min_amount() {
        LoyaltyBingeEarningRule rule = rule(10L, "1.00",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1000.00"), null);
        when(configService.resolveEarningRule(eq(500L), eq("GOLD"), any())).thenReturn(Optional.of(rule));

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-6", new BigDecimal("500.00"), LocalDateTime.now(), "c"));

        assertThat(result.skipReason()).isEqualTo("BELOW_MIN_AMOUNT");
        verifyNoInteractions(walletService);
    }

    @Test
    void reuses_existing_qualification_event_on_replay() {
        LoyaltyBingeEarningRule rule = rule(10L, "1.00",
                BigDecimal.ONE, BigDecimal.ONE, null, null);
        when(configService.resolveEarningRule(eq(500L), eq("GOLD"), any())).thenReturn(Optional.of(rule));
        when(qcEventRepository.findByMembershipIdAndBookingRef(42L, "BK-7"))
                .thenReturn(java.util.List.of(
                        LoyaltyQualificationEvent.builder().id(123L).qualificationCredits(999L).build()));

        var result = engine.earnForBooking(new EarnEngine.EarnRequest(
                42L, 7L, "BK-7", new BigDecimal("1000.00"), LocalDateTime.now(), "c"));

        assertThat(result.awarded()).isTrue();
        verify(qcEventRepository, never()).save(any());
    }

    private LoyaltyBingeEarningRule rule(long numerator, String denominator,
                                         BigDecimal tierMultiplier, BigDecimal qcMultiplier,
                                         BigDecimal minAmount, Long cap) {
        return LoyaltyBingeEarningRule.builder()
                .id(9000L).bindingId(500L).ruleType("FLAT_PER_AMOUNT")
                .pointsNumerator(numerator)
                .amountDenominator(new BigDecimal(denominator))
                .tierMultiplier(tierMultiplier)
                .qcMultiplier(qcMultiplier)
                .minBookingAmount(minAmount)
                .capPerBooking(cap)
                .effectiveFrom(LocalDateTime.now().minusDays(30))
                .build();
    }
}
