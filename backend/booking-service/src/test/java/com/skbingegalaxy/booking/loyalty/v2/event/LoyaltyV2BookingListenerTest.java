package com.skbingegalaxy.booking.loyalty.v2.event;

import com.skbingegalaxy.booking.event.BookingCancelledEvent;
import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.EarnEngine;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.TierEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyLedgerEntry;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyPointsWallet;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyLedgerEntryRepository;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyPointsWalletRepository;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the earn claw-back on cancellation never silently keeps points the
 * member shouldn't have when the earned points were already spent/expired.
 * Before the fix, a plain debit() threw InsufficientPointsException (swallowed by
 * the listener's outer catch), so the member kept ALL the points.
 */
@ExtendWith(MockitoExtension.class)
class LoyaltyV2BookingListenerTest {

    @Mock private EarnEngine earnEngine;
    @Mock private TierEngine tierEngine;
    @Mock private PointsWalletService walletService;
    @Mock private EnrollmentService enrollmentService;
    @Mock private LoyaltyLedgerEntryRepository ledgerRepository;
    @Mock private LoyaltyPointsWalletRepository walletRepository;

    @InjectMocks private LoyaltyV2BookingListener listener;

    private BookingCancelledEvent fullRefundCancel() {
        // refundAmount == totalAmount => proportion 1.0 => reverse the full earned amount
        return new BookingCancelledEvent(
                1L, "BK1", 7L, 3L, null,
                new BigDecimal("1000"), new BigDecimal("1000"),
                "customer cancel", LocalDateTime.now());
    }

    private void wireMembershipWalletAndEarn(long walletBalance, long earnedPoints) {
        LoyaltyMembership membership = org.mockito.Mockito.mock(LoyaltyMembership.class);
        when(membership.getId()).thenReturn(1L);
        LoyaltyPointsWallet wallet = org.mockito.Mockito.mock(LoyaltyPointsWallet.class);
        when(wallet.getId()).thenReturn(10L);
        when(wallet.getCurrentBalance()).thenReturn(walletBalance);
        LoyaltyLedgerEntry earn = org.mockito.Mockito.mock(LoyaltyLedgerEntry.class);
        when(earn.getPointsDelta()).thenReturn(earnedPoints);

        when(enrollmentService.findForCustomer(anyLong())).thenReturn(Optional.of(membership));
        when(walletRepository.findByMembershipId(1L)).thenReturn(Optional.of(wallet));
        when(ledgerRepository.findByWalletIdAndBookingRefAndEntryType(
                eq(10L), eq("BK1"), eq(LoyaltyV2Constants.LEDGER_REDEEM))).thenReturn(List.of());
        when(ledgerRepository.findByWalletIdAndBookingRefAndEntryType(
                eq(10L), eq("BK1"), eq(LoyaltyV2Constants.LEDGER_EARN))).thenReturn(List.of(earn));
    }

    @Test
    void clawBack_capsToAvailableBalance_whenPointsAlreadySpent() {
        // Earned 100, but only 30 left in the wallet (rest already spent).
        wireMembershipWalletAndEarn(30L, 100L);

        listener.onBookingCancelled(fullRefundCancel());

        ArgumentCaptor<PointsWalletService.DebitRequest> captor =
                ArgumentCaptor.forClass(PointsWalletService.DebitRequest.class);
        verify(walletService).debit(captor.capture());
        // Capped to the 30 actually available — NOT the full 100.
        assertThat(captor.getValue().points()).isEqualTo(30L);
    }

    @Test
    void clawBack_fullAmount_whenBalanceSufficient() {
        wireMembershipWalletAndEarn(500L, 100L);

        listener.onBookingCancelled(fullRefundCancel());

        ArgumentCaptor<PointsWalletService.DebitRequest> captor =
                ArgumentCaptor.forClass(PointsWalletService.DebitRequest.class);
        verify(walletService).debit(captor.capture());
        assertThat(captor.getValue().points()).isEqualTo(100L);
    }

    @Test
    void clawBack_noDebit_whenBalanceZero() {
        // All earned points already spent — nothing to claw back; must not throw or debit.
        wireMembershipWalletAndEarn(0L, 100L);

        listener.onBookingCancelled(fullRefundCancel());

        verify(walletService, never()).debit(any());
    }

    @Test
    void redeemRefund_clampsProportionToOne_onOverRefund() {
        // Misconfigured tier / bad event: refundAmount (2000) > totalAmount (1000) => raw proportion 2.0.
        // Must clamp to 1.0 so we never credit back MORE points than were originally redeemed.
        LoyaltyMembership membership = org.mockito.Mockito.mock(LoyaltyMembership.class);
        when(membership.getId()).thenReturn(1L);
        LoyaltyPointsWallet wallet = org.mockito.Mockito.mock(LoyaltyPointsWallet.class);
        when(wallet.getId()).thenReturn(10L);
        LoyaltyLedgerEntry redeem = org.mockito.Mockito.mock(LoyaltyLedgerEntry.class);
        when(redeem.getPointsDelta()).thenReturn(-200L);   // 200 points originally redeemed

        when(enrollmentService.findForCustomer(anyLong())).thenReturn(Optional.of(membership));
        when(walletRepository.findByMembershipId(1L)).thenReturn(Optional.of(wallet));
        when(ledgerRepository.findByWalletIdAndBookingRefAndEntryType(
                eq(10L), eq("BK1"), eq(LoyaltyV2Constants.LEDGER_REDEEM))).thenReturn(List.of(redeem));
        when(ledgerRepository.findByWalletIdAndBookingRefAndEntryType(
                eq(10L), eq("BK1"), eq(LoyaltyV2Constants.LEDGER_EARN))).thenReturn(List.of());

        BookingCancelledEvent overRefund = new BookingCancelledEvent(
                1L, "BK1", 7L, 3L, null,
                new BigDecimal("1000"), new BigDecimal("2000"),   // refund > total
                "over refund", LocalDateTime.now());

        listener.onBookingCancelled(overRefund);

        ArgumentCaptor<PointsWalletService.CreditRequest> captor =
                ArgumentCaptor.forClass(PointsWalletService.CreditRequest.class);
        verify(walletService).credit(captor.capture());
        // 200 * clamped(1.0) == 200 — NOT 400 (which is what the un-clamped 2.0 ratio would give).
        assertThat(captor.getValue().points()).isEqualTo(200L);
    }
}
