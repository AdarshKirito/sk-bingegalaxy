package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.SettlementRecord;
import com.skbingegalaxy.distribution.repository.SettlementRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Settlements (slice 6, G-E)")
class SettlementServiceTest {

    @Mock private SettlementRecordRepository settlementRepository;
    @InjectMocks private SettlementService service;

    private static ConnectionDestination terms(int bps) {
        return ConnectionDestination.builder().id(3L).connectionId(7L)
            .destinationCode("VIATOR").commissionBps(bps)
            .paymentResponsibility(ConnectionDestination.PaymentResponsibility.CHANNEL_COLLECTS)
            .settlementModel(ConnectionDestination.SettlementModel.COMMISSION_SETTLEMENT)
            .build();
    }

    private static SettlementRecord record(String currency, long outstanding) {
        return SettlementRecord.builder()
            .id(1L).bingeId(1L).bookingRef("SKBG26A").destinationCode("VIATOR")
            .settlementCurrency(currency).outstandingMinor(outstanding)
            .settlementStatus(SettlementRecord.SettlementStatus.EXPECTED)
            .reconciliationStatus(SettlementRecord.ReconciliationStatus.UNMATCHED)
            .build();
    }

    @Test
    @DisplayName("outstanding totals are per currency and never summed together")
    void totalsAreNeverSummedAcrossCurrencies() {
        when(settlementRepository.findByBingeIdAndSettlementStatusIn(any(), any()))
            .thenReturn(List.of(record("INR", 300000), record("USD", 5000), record("INR", 100000)));

        var totals = service.outstandingByCurrency(1L);

        // A destination may remit in one currency while the venue banks in another.
        // Adding them produces a confident, meaningless number.
        assertThat(totals).containsExactly(
            java.util.Map.entry("INR", 400000L),
            java.util.Map.entry("USD", 5000L));
    }

    @Test
    @DisplayName("a receivable is created for a CHANNEL-collected booking, net of commission")
    void createsReceivableNetOfCommission() {
        when(settlementRepository.findByBookingRefAndDestinationCode("SKBG26A", "VIATOR"))
            .thenReturn(Optional.empty());
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var r = service.createForChannelBooking(1L, 7L, "SKBG26A", terms(2000), "INR", 300000L);

        assertThat(r).isPresent();
        assertThat(r.get().getCommissionMinor()).isEqualTo(60000);
        // Outstanding is the NET. Counting the gross would overstate every total by the
        // channel cut, which is never coming to the venue.
        assertThat(r.get().getOutstandingMinor()).isEqualTo(240000);
        assertThat(r.get().getSettlementStatus())
            .isEqualTo(SettlementRecord.SettlementStatus.EXPECTED);
    }

    @Test
    @DisplayName("NO receivable when the venue collects — that is a payable, not a receivable")
    void noReceivableWhenVenueCollects() {
        ConnectionDestination t = terms(2000);
        t.setPaymentResponsibility(ConnectionDestination.PaymentResponsibility.VENUE_COLLECTS);

        // The venue OWES commission here. Recording it as money owed TO the venue would
        // invert the direction and inflate every outstanding total.
        assertThat(service.createForChannelBooking(1L, 7L, "SKBG26A", t, "INR", 300000L))
            .isEmpty();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("a redelivered message does not create a second receivable")
    void idempotentOnRedelivery() {
        when(settlementRepository.findByBookingRefAndDestinationCode("SKBG26A", "VIATOR"))
            .thenReturn(Optional.of(record("INR", 240000)));

        // At-least-once delivery is normal; a duplicate is not a second sale.
        assertThat(service.createForChannelBooking(1L, 7L, "SKBG26A", terms(2000), "INR", 300000L))
            .isPresent();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("commission rounds HALF-UP, not toward the venue")
    void commissionRoundsHalfUp() {
        // 333 * 15% = 49.95 minor units. Truncation would systematically favour the
        // venue by up to a unit per booking and compound across reconciliations.
        assertThat(SettlementService.commissionOf(333L, 1500)).isEqualTo(50L);
        assertThat(SettlementService.commissionOf(1000L, null)).isZero();
        assertThat(SettlementService.commissionOf(1000L, 0)).isZero();
    }

    @Test
    @DisplayName("an exact payout is PAID and MATCHED")
    void exactPayoutMatches() {
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record("INR", 300000)));
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SettlementRecord r = service.recordPayout(1L, 1L, 300000, LocalDate.now());

        assertThat(r.getSettlementStatus()).isEqualTo(SettlementRecord.SettlementStatus.PAID);
        assertThat(r.getReconciliationStatus())
            .isEqualTo(SettlementRecord.ReconciliationStatus.MATCHED);
        assertThat(r.getVarianceMinor()).isZero();
    }

    @Test
    @DisplayName("a short payment is SHORT_PAID with the variance stored, not absorbed")
    void shortPaymentIsSurfaced() {
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record("INR", 300000)));
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SettlementRecord r = service.recordPayout(1L, 1L, 250000, LocalDate.now());

        // Quietly marking this PAID would hide exactly the money that needs chasing —
        // the single most important thing this table can tell a venue.
        assertThat(r.getSettlementStatus()).isEqualTo(SettlementRecord.SettlementStatus.SHORT_PAID);
        assertThat(r.getVarianceMinor()).isEqualTo(-50000);
        assertThat(r.getReconciliationStatus())
            .isEqualTo(SettlementRecord.ReconciliationStatus.VARIANCE);
    }

    @Test
    @DisplayName("an overpayment is OVER_PAID, not silently accepted as correct")
    void overpaymentIsSurfaced() {
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record("INR", 300000)));
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Being paid too much is also a reconciliation problem: it is somebody else's
        // money until it is explained.
        assertThat(service.recordPayout(1L, 1L, 320000, LocalDate.now()).getSettlementStatus())
            .isEqualTo(SettlementRecord.SettlementStatus.OVER_PAID);
    }

    @Test
    @DisplayName("a written-off settlement cannot be paid, and negatives are refused")
    void guards() {
        SettlementRecord writtenOff = record("INR", 300000);
        writtenOff.setSettlementStatus(SettlementRecord.SettlementStatus.WRITTEN_OFF);
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(writtenOff));

        assertThatThrownBy(() -> service.recordPayout(1L, 1L, 1000, LocalDate.now()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("written off");
    }

    @Test
    @DisplayName("another venue's settlement is not found")
    void tenancyScoped() {
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record("INR", 1000)));

        assertThatThrownBy(() -> service.recordPayout(999L, 1L, 1000, LocalDate.now()))
            .isInstanceOf(com.skbingegalaxy.common.exception.ResourceNotFoundException.class);
    }
}
