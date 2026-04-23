package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TierEngine}.
 *
 * <p>Verifies the four key contracts:
 * <ul>
 *   <li>promotion fires immediately on qualification,</li>
 *   <li>demotion is DEFERRED — high tier stays until {@code tierEffectiveUntil} passes,</li>
 *   <li>annual rollover demotes to soft-landing tier (one step down),</li>
 *   <li>re-qualification at the same tier extends the validity window.</li>
 * </ul>
 */
class TierEngineTest {

    private LoyaltyConfigService configService;
    private LoyaltyMembershipRepository membershipRepository;
    private LoyaltyQualificationEventRepository qcEventRepository;
    private LoyaltyMembershipEventRepository membershipEventRepository;

    private TierEngine engine;

    private LoyaltyProgram program;
    private List<LoyaltyTierDefinition> ladder;

    @BeforeEach
    void setup() {
        configService = mock(LoyaltyConfigService.class);
        membershipRepository = mock(LoyaltyMembershipRepository.class);
        qcEventRepository = mock(LoyaltyQualificationEventRepository.class);
        membershipEventRepository = mock(LoyaltyMembershipEventRepository.class);

        engine = new TierEngine(configService, membershipRepository,
                qcEventRepository, membershipEventRepository);

        program = LoyaltyProgram.builder().id(1L).code("SK").build();
        ladder = List.of(
                tier("BRONZE",   1, 0L,       null, 1),
                tier("SILVER",   2, 20_000L,  null, 1),
                tier("GOLD",     3, 50_000L,  null, 1),
                tier("PLATINUM", 4, 100_000L, null, 1)
        );

        when(configService.requireDefaultProgram()).thenReturn(program);
        when(configService.activeLadder(eq(1L), any())).thenReturn(ladder);
        when(membershipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(membershipEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void promotion_fires_immediately_when_window_qc_crosses_threshold() {
        LoyaltyMembership m = member("BRONZE", LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(membershipRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(m));
        when(qcEventRepository.sumActiveCredits(eq(42L), any())).thenReturn(60_000L);   // qualifies Gold
        when(qcEventRepository.sumLifetimeCredits(42L)).thenReturn(60_000L);

        LocalDateTime now = LocalDateTime.of(2026, 5, 10, 12, 0);
        engine.recalculateTier(42L, now);

        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");
        assertThat(m.getTierEffectiveFrom()).isEqualTo(now);
        // validityCalendarYearsAfter = 1 → through end of 2027
        assertThat(m.getTierEffectiveUntil().getYear()).isEqualTo(2027);
        assertThat(m.getTierEffectiveUntil().getMonthValue()).isEqualTo(12);

        ArgumentCaptor<LoyaltyMembershipEvent> evt = ArgumentCaptor.forClass(LoyaltyMembershipEvent.class);
        verify(membershipEventRepository).save(evt.capture());
        assertThat(evt.getValue().getEventType()).isEqualTo("TIER_UP");
    }

    @Test
    void demotion_is_deferred_until_validity_window_passes() {
        // Member holds GOLD through end-of-2027.  Their QC dropped today.
        LoyaltyMembership m = member("GOLD", LocalDateTime.of(2027, 12, 31, 23, 59, 59));
        when(membershipRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(m));
        when(qcEventRepository.sumActiveCredits(eq(42L), any())).thenReturn(10_000L);   // only qualifies Bronze
        when(qcEventRepository.sumLifetimeCredits(42L)).thenReturn(10_000L);

        engine.recalculateTier(42L, LocalDateTime.of(2026, 6, 1, 12, 0));

        // Stays at GOLD — no demotion mid-year.
        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");
        verify(membershipEventRepository, never()).save(any());
    }

    @Test
    void annual_rollover_demotes_to_soft_landing_tier() {
        LoyaltyMembership m = member("PLATINUM", LocalDateTime.of(2026, 1, 1, 0, 0));
        m.setSoftLandingEligible(true);
        when(membershipRepository.findTierExpiringBy(eq(1L), any())).thenReturn(List.of(m));
        when(membershipRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(m));
        when(qcEventRepository.sumActiveCredits(eq(42L), any())).thenReturn(10_000L);   // only Bronze now
        when(qcEventRepository.sumLifetimeCredits(42L)).thenReturn(10_000L);

        int demoted = engine.runAnnualRollover(LocalDateTime.of(2026, 2, 1, 3, 0));

        assertThat(demoted).isEqualTo(1);
        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");                           // soft landed one down
        assertThat(m.isSoftLandingEligible()).isFalse();                                // used up

        ArgumentCaptor<LoyaltyMembershipEvent> evt = ArgumentCaptor.forClass(LoyaltyMembershipEvent.class);
        verify(membershipEventRepository).save(evt.capture());
        assertThat(evt.getValue().getEventType()).isEqualTo("TIER_SOFT_LANDED");
    }

    @Test
    void rollover_extends_window_when_requalified() {
        LoyaltyMembership m = member("GOLD", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(membershipRepository.findTierExpiringBy(eq(1L), any())).thenReturn(List.of(m));
        when(membershipRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(m));
        when(qcEventRepository.sumActiveCredits(eq(42L), any())).thenReturn(55_000L);   // still Gold
        when(qcEventRepository.sumLifetimeCredits(42L)).thenReturn(55_000L);

        int demoted = engine.runAnnualRollover(LocalDateTime.of(2026, 2, 1, 3, 0));

        assertThat(demoted).isEqualTo(0);
        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");
        assertThat(m.getTierEffectiveUntil().getYear()).isEqualTo(2027);
    }

    @Test
    void requalification_at_same_tier_extends_validity_window() {
        LoyaltyMembership m = member("GOLD", LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(membershipRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(m));
        when(qcEventRepository.sumActiveCredits(eq(42L), any())).thenReturn(55_000L);
        when(qcEventRepository.sumLifetimeCredits(42L)).thenReturn(55_000L);

        engine.recalculateTier(42L, LocalDateTime.of(2026, 10, 15, 9, 0));

        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");
        // Window pushed to end of 2027 (one calendar year after 2026).
        assertThat(m.getTierEffectiveUntil().getYear()).isEqualTo(2027);

        ArgumentCaptor<LoyaltyMembershipEvent> evt = ArgumentCaptor.forClass(LoyaltyMembershipEvent.class);
        verify(membershipEventRepository).save(evt.capture());
        assertThat(evt.getValue().getEventType()).isEqualTo("TIER_REQUALIFIED");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private LoyaltyMembership member(String tier, LocalDateTime validUntil) {
        return LoyaltyMembership.builder()
                .id(42L).tenantId(1L).programId(1L).customerId(100L)
                .memberNumber("SK-T-42")
                .currentTierCode(tier)
                .tierEffectiveFrom(LocalDateTime.of(2025, 1, 1, 0, 0))
                .tierEffectiveUntil(validUntil)
                .build();
    }

    private LoyaltyTierDefinition tier(String code, int rank, long qcRequired,
                                       Long lifetimeRequired, Integer validityYears) {
        LoyaltyTierDefinition t = LoyaltyTierDefinition.builder()
                .id((long) rank).programId(1L).tenantId(1L)
                .code(code).displayName(code)
                .rankOrder(rank)
                .qualificationCreditsRequired(qcRequired)
                .qualificationWindowDays(365)
                .lifetimeCreditsRequired(lifetimeRequired)
                .validityCalendarYearsAfter(validityYears)
                .build();
        // Soft landing: one step down (BRONZE has none).
        if ("SILVER".equals(code)) t.setSoftLandingTierCode("BRONZE");
        if ("GOLD".equals(code)) t.setSoftLandingTierCode("SILVER");
        if ("PLATINUM".equals(code)) t.setSoftLandingTierCode("GOLD");
        return t;
    }
}
