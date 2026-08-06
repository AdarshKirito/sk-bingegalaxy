package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Settlements (slice 6, G-E)")
class SettlementServiceTest {

    @Mock private SettlementRecordRepository settlementRepository;
    @InjectMocks private SettlementService service;

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
