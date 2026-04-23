package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsLot;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsLotRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ExpiryEngine}.
 *
 * <p>Confirms the engine drives {@link PointsWalletService#expireLot}
 * for every due lot and tolerates a single bad row without aborting
 * the nightly run.
 */
class ExpiryEngineTest {

    private LoyaltyPointsLotRepository lotRepository;
    private LoyaltyPointsWalletRepository walletRepository;
    private PointsWalletService walletService;
    private TierEngine tierEngine;

    private ExpiryEngine engine;

    @BeforeEach
    void setup() {
        lotRepository = mock(LoyaltyPointsLotRepository.class);
        walletRepository = mock(LoyaltyPointsWalletRepository.class);
        walletService = mock(PointsWalletService.class);
        tierEngine = mock(TierEngine.class);

        engine = new ExpiryEngine(lotRepository, walletRepository, walletService, tierEngine);
    }

    @Test
    void expireAsOf_returns_zero_when_nothing_due() {
        when(lotRepository.findExpiringLots(any())).thenReturn(List.of());

        int n = engine.expireAsOf(LocalDateTime.now());

        assertThat(n).isZero();
        verifyNoInteractions(walletService);
    }

    @Test
    void expireAsOf_processes_each_due_lot() {
        LoyaltyPointsLot lot1 = LoyaltyPointsLot.builder().id(10L).walletId(1L).remainingPoints(500L).build();
        LoyaltyPointsLot lot2 = LoyaltyPointsLot.builder().id(11L).walletId(2L).remainingPoints(300L).build();
        when(lotRepository.findExpiringLots(any())).thenReturn(List.of(lot1, lot2));
        when(lotRepository.findById(10L)).thenReturn(Optional.of(lot1));
        when(lotRepository.findById(11L)).thenReturn(Optional.of(lot2));
        when(walletRepository.findById(1L)).thenReturn(Optional.of(
                LoyaltyPointsWallet.builder().id(1L).membershipId(42L).build()));
        when(walletRepository.findById(2L)).thenReturn(Optional.of(
                LoyaltyPointsWallet.builder().id(2L).membershipId(43L).build()));

        LocalDateTime now = LocalDateTime.now();
        int n = engine.expireAsOf(now);

        assertThat(n).isEqualTo(2);
        verify(walletService).expireLot(42L, 10L, now);
        verify(walletService).expireLot(43L, 11L, now);
        verify(tierEngine).recalculateTier(42L, now);
        verify(tierEngine).recalculateTier(43L, now);
    }

    @Test
    void expireAsOf_continues_when_single_lot_fails() {
        LoyaltyPointsLot bad = LoyaltyPointsLot.builder().id(10L).walletId(1L).remainingPoints(500L).build();
        LoyaltyPointsLot good = LoyaltyPointsLot.builder().id(11L).walletId(2L).remainingPoints(300L).build();
        when(lotRepository.findExpiringLots(any())).thenReturn(List.of(bad, good));
        when(lotRepository.findById(10L)).thenReturn(Optional.of(bad));
        when(lotRepository.findById(11L)).thenReturn(Optional.of(good));
        when(walletRepository.findById(1L)).thenReturn(Optional.empty());   // forces exception path
        when(walletRepository.findById(2L)).thenReturn(Optional.of(
                LoyaltyPointsWallet.builder().id(2L).membershipId(43L).build()));

        int n = engine.expireAsOf(LocalDateTime.now());

        assertThat(n).isEqualTo(1);                        // only the good one succeeded
        verify(walletService).expireLot(eq(43L), eq(11L), any());
    }

    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
}
