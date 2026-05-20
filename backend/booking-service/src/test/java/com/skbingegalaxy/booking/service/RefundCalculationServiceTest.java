package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.BookingPriceSnapshot;
import com.skbingegalaxy.booking.repository.BookingPriceSnapshotRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CreditNoteRepository;
import com.skbingegalaxy.booking.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-math tests for {@link RefundCalculationService#quote}. The contract:
 * <ul>
 *   <li>Tax is reversed proportionally to the refunded subtotal, not the
 *       gross refund.</li>
 *   <li>Cancellation fee is netted from the request before computing the
 *       reversal ratio (so platform-retained fee is not over-refunded).</li>
 *   <li>Ratio is clamped to [0,1] — never refund more tax than was charged.</li>
 *   <li>Snapshot is the immutable source of truth — refunds never re-read
 *       current tax rules or FX.</li>
 * </ul>
 */
class RefundCalculationServiceTest {

    private RefundCalculationService service;
    private BookingPriceSnapshotRepository snapshotRepo;
    private CreditNoteRepository creditNoteRepo;

    @BeforeEach
    void setUp() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        snapshotRepo = mock(BookingPriceSnapshotRepository.class);
        InvoiceRepository invoiceRepo = mock(InvoiceRepository.class);
        creditNoteRepo = mock(CreditNoteRepository.class);
        LedgerService ledgerService = mock(LedgerService.class);
        service = new RefundCalculationService(
            bookingRepo, snapshotRepo, invoiceRepo, creditNoteRepo, ledgerService);
        when(creditNoteRepo.findByBookingRefOrderByCreatedAtDesc(anyString()))
            .thenReturn(List.of());
    }

    /** A snapshot with INR 1000 subtotal + INR 180 GST (18%) => INR 1180 total. */
    private void snapshot(BigDecimal totalBase, BigDecimal taxBase) {
        BookingPriceSnapshot snap = BookingPriceSnapshot.builder()
            .bookingRef("BK-1")
            .baseCurrencyCode("INR")
            .totalBase(totalBase)
            .taxAmountBase(taxBase)
            .build();
        when(snapshotRepo.findByBookingRefOrderByCreatedAtDesc("BK-1"))
            .thenReturn(List.of(snap));
    }

    @Test
    void fullRefund_reversesAllTax() {
        snapshot(new BigDecimal("1180.00"), new BigDecimal("180.00"));
        var q = service.quote("BK-1", new BigDecimal("1180.00"), BigDecimal.ZERO);

        assertThat(q.refundedSubtotal()).isEqualByComparingTo("1180.00");
        assertThat(q.reversedTax()).isEqualByComparingTo("180.00");
        assertThat(q.creditNoteAmount()).isEqualByComparingTo("1360.00"); // 1180 + 180
        assertThat(q.taxReversalRatio()).isEqualByComparingTo("1.0000000000");
    }

    @Test
    void halfRefund_reversesProportionalTax() {
        snapshot(new BigDecimal("1180.00"), new BigDecimal("180.00"));
        // Refund half the subtotal-without-tax
        var q = service.quote("BK-1", new BigDecimal("500.00"), BigDecimal.ZERO);

        // refundedSubtotal=500, ratio=500/1000=0.5, reversedTax=180*0.5=90
        assertThat(q.reversedTax()).isEqualByComparingTo("90.00");
        assertThat(q.creditNoteAmount()).isEqualByComparingTo("590.00");
    }

    @Test
    void cancellationFee_reducesRefundableAndTaxReversal() {
        snapshot(new BigDecimal("1180.00"), new BigDecimal("180.00"));
        // Customer cancels: refund 1180 minus 200 platform fee
        var q = service.quote("BK-1", new BigDecimal("1180.00"), new BigDecimal("200.00"));

        assertThat(q.refundedSubtotal()).isEqualByComparingTo("980.00");
        // ratio = 980/1000 = 0.98, reversedTax = 180*0.98 = 176.40
        assertThat(q.reversedTax()).isEqualByComparingTo("176.40");
        assertThat(q.creditNoteAmount()).isEqualByComparingTo("1156.40");
    }

    @Test
    void ratioIsClamped_neverOver100Percent() {
        snapshot(new BigDecimal("1180.00"), new BigDecimal("180.00"));
        // Caller passes a refund larger than the booking total — should clamp.
        var q = service.quote("BK-1", new BigDecimal("9999.00"), BigDecimal.ZERO);
        assertThat(q.taxReversalRatio()).isEqualByComparingTo("1");
        assertThat(q.reversedTax()).isEqualByComparingTo("180.00");
    }

    @Test
    void cancellationFeeCannotExceedRefundRequest() {
        snapshot(new BigDecimal("1180.00"), new BigDecimal("180.00"));
        // Fee > refund => fee is capped at the refund itself => refundedSubtotal = 0
        var q = service.quote("BK-1", new BigDecimal("100.00"), new BigDecimal("500.00"));
        assertThat(q.cancellationFee()).isEqualByComparingTo("100.00");
        assertThat(q.refundedSubtotal()).isEqualByComparingTo("0");
        assertThat(q.reversedTax()).isEqualByComparingTo("0");
    }

    @Test
    void zeroTaxBooking_yieldsZeroReversal() {
        snapshot(new BigDecimal("500.00"), BigDecimal.ZERO);
        var q = service.quote("BK-1", new BigDecimal("500.00"), BigDecimal.ZERO);
        assertThat(q.reversedTax()).isEqualByComparingTo("0");
        assertThat(q.creditNoteAmount()).isEqualByComparingTo("500.00");
    }
}
