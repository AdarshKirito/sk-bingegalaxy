package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyLedgerEntry;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyLedgerEntryRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsLotRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PointsWalletServiceTest {

    private LoyaltyPointsWalletRepository walletRepository;
    private LoyaltyPointsLotRepository lotRepository;
    private LoyaltyLedgerEntryRepository ledgerRepository;
    private PointsWalletService service;

    @BeforeEach
    void setup() {
        walletRepository = mock(LoyaltyPointsWalletRepository.class);
        lotRepository = mock(LoyaltyPointsLotRepository.class);
        ledgerRepository = mock(LoyaltyLedgerEntryRepository.class);
        service = new PointsWalletService(walletRepository, lotRepository, ledgerRepository);
    }

    @Test
    void debit_idempotency_hit_returns_existing_per_lot_rows_for_same_wallet_and_key() {
        LoyaltyPointsWallet wallet = LoyaltyPointsWallet.builder()
                .id(5L).membershipId(42L).currentBalance(10_000L).build();
        List<LoyaltyLedgerEntry> existing = List.of(
                LoyaltyLedgerEntry.builder().id(10L).walletId(5L).entryType("REDEEM").pointsDelta(-500L).build(),
                LoyaltyLedgerEntry.builder().id(11L).walletId(5L).entryType("REDEEM").pointsDelta(-250L).build()
        );

        when(walletRepository.findByMembershipIdForUpdate(42L)).thenReturn(Optional.of(wallet));
        when(ledgerRepository.existsByWalletIdAndEntryTypeAndIdempotencyKey(
                5L, LoyaltyV2Constants.LEDGER_REDEEM, "redeem:key")).thenReturn(true);
        when(ledgerRepository.findByWalletIdAndEntryTypeAndIdempotencyKeyStartingWithOrderByIdAsc(
                5L, LoyaltyV2Constants.LEDGER_REDEEM, "redeem:key#lot=")).thenReturn(existing);

        List<LoyaltyLedgerEntry> result = service.debit(new PointsWalletService.DebitRequest(
                42L, 750L, LoyaltyV2Constants.LEDGER_REDEEM, 7L, "BK-1",
                "redeem:key", "corr", "BOOKING_REDEMPTION", "retry", null, "CUSTOMER"));

        assertThat(result).containsExactlyElementsOf(existing);
        verifyNoInteractions(lotRepository);
        verify(ledgerRepository, never()).findByBookingRef(anyString());
    }
}
