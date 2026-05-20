package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingPriceSnapshot;
import com.skbingegalaxy.booking.entity.CreditNote;
import com.skbingegalaxy.booking.entity.Invoice;
import com.skbingegalaxy.booking.entity.LedgerEntry;
import com.skbingegalaxy.booking.repository.BookingPriceSnapshotRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CreditNoteRepository;
import com.skbingegalaxy.booking.repository.InvoiceRepository;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.common.money.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Calculates refund amounts (with proportional tax reversal) and issues
 * {@link CreditNote} rows. The original {@link BookingPriceSnapshot} is
 * the source of truth — refunds never recompute taxes from current rules,
 * which guarantees deterministic refunds even after FX or tax-rate moves.
 *
 * <p>Refund logic:
 * <pre>
 *   refundedSubtotal     = requestedRefund - cancellationFee
 *   subtotalWithoutTax   = snapshot.totalBase - snapshot.taxAmountBase
 *   taxReversalRatio     = refundedSubtotal / subtotalWithoutTax (clamped to [0,1])
 *   reversedTax          = snapshot.taxAmountBase * taxReversalRatio
 *   creditNoteAmount     = refundedSubtotal + reversedTax
 * </pre>
 *
 * <p>Issues paired ledger entries: REFUND (debit) + TAX_REVERSAL (credit
 * → debit reversal of the original TAX_COLLECTED entry).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RefundCalculationService {

    private static final String CN_PREFIX = "SKBG-CN";
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final AtomicLong COUNTER = new AtomicLong(System.currentTimeMillis() % 100_000);

    private final BookingRepository bookingRepo;
    private final BookingPriceSnapshotRepository snapshotRepo;
    private final InvoiceRepository invoiceRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final LedgerService ledgerService;

    /** Result of a refund calculation prior to issuing a credit note. */
    public record RefundQuote(
        BigDecimal requestedRefund,
        BigDecimal cancellationFee,
        BigDecimal refundedSubtotal,
        BigDecimal reversedTax,
        BigDecimal creditNoteAmount,
        BigDecimal remainingRefundableAfter,
        String currencyCode,
        BigDecimal taxReversalRatio
    ) {}

    /**
     * Compute (but do not persist) what a refund of {@code requestedRefund}
     * (in BASE currency) would look like for {@code bookingRef}, given an
     * optional cancellation fee.
     */
    @Transactional(readOnly = true)
    public RefundQuote quote(String bookingRef, BigDecimal requestedRefund, BigDecimal cancellationFee) {
        BookingPriceSnapshot snapshot = latestSnapshot(bookingRef);
        BigDecimal totalBase = MoneyUtil.zeroIfNull(snapshot.getTotalBase());
        BigDecimal taxBase = MoneyUtil.zeroIfNull(snapshot.getTaxAmountBase());
        BigDecimal subtotalWithoutTax = totalBase.subtract(taxBase);

        BigDecimal refundReq = MoneyUtil.zeroIfNull(requestedRefund).max(BigDecimal.ZERO);
        BigDecimal cancelFee = MoneyUtil.zeroIfNull(cancellationFee).max(BigDecimal.ZERO);
        // Cancellation fee can never exceed the refund request.
        if (cancelFee.compareTo(refundReq) > 0) cancelFee = refundReq;

        BigDecimal refundedSubtotal = refundReq.subtract(cancelFee).max(BigDecimal.ZERO);

        BigDecimal ratio = subtotalWithoutTax.signum() > 0
            ? refundedSubtotal.divide(subtotalWithoutTax, 10, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        if (ratio.compareTo(BigDecimal.ONE) > 0) ratio = BigDecimal.ONE;

        BigDecimal reversedTax = MoneyUtil.round(taxBase.multiply(ratio), snapshot.getBaseCurrencyCode());
        BigDecimal creditAmount = MoneyUtil.round(refundedSubtotal.add(reversedTax), snapshot.getBaseCurrencyCode());

        // Remaining refundable = totalBase - sum(existing credit notes) - this credit
        BigDecimal alreadyCredited = creditNoteRepo.findByBookingRefOrderByCreatedAtDesc(bookingRef).stream()
            .filter(c -> c.getStatus() == CreditNote.Status.ISSUED)
            .map(CreditNote::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = totalBase.subtract(alreadyCredited).subtract(creditAmount).max(BigDecimal.ZERO);

        return new RefundQuote(
            refundReq, cancelFee, refundedSubtotal, reversedTax, creditAmount,
            remaining, snapshot.getBaseCurrencyCode(), ratio
        );
    }

    /**
     * Issue a credit note + matching ledger entries for {@code bookingRef}.
     * Idempotent against duplicate triggers via the entry-uuid scheme on
     * {@link LedgerService}.
     */
    public CreditNote issue(String bookingRef, BigDecimal requestedRefund, BigDecimal cancellationFee,
                            CreditNote.Reason reason, String createdBy) {
        Booking booking = bookingRepo.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
        Invoice invoice = invoiceRepo.findFirstByBookingRefOrderByIdDesc(bookingRef).orElse(null);
        RefundQuote q = quote(bookingRef, requestedRefund, cancellationFee);

        CreditNote cn = CreditNote.builder()
            .creditNoteNumber(nextNumber())
            .invoiceId(invoice != null ? invoice.getId() : null)
            .bookingRef(bookingRef)
            .currencyCode(q.currencyCode())
            .amount(q.creditNoteAmount())
            .taxAmount(q.reversedTax())
            .reason(reason != null ? reason : CreditNote.Reason.CUSTOMER_CANCELLATION)
            .status(CreditNote.Status.ISSUED)
            .createdBy(createdBy)
            .build();
        cn = creditNoteRepo.save(cn);

        // Ledger: REFUND (debit — cash leaving the platform), TAX_REVERSAL (debit reversal of TAX_COLLECTED)
        if (q.refundedSubtotal().signum() > 0 || q.creditNoteAmount().signum() > 0) {
            ledgerService.record(LedgerEntry.builder()
                .entryUuid("cn-" + cn.getId() + "-refund")
                .bookingRef(bookingRef)
                .invoiceId(invoice != null ? invoice.getId() : null)
                .creditNoteId(cn.getId())
                .bingeId(booking.getBingeId())
                .customerId(booking.getCustomerId())
                .entryType(LedgerEntry.EntryType.REFUND)
                .direction(LedgerEntry.Direction.DEBIT)
                .amount(q.creditNoteAmount())
                .currencyCode(q.currencyCode())
                .recordedBy(createdBy)
                .build());
        }
        if (q.reversedTax().signum() > 0) {
            ledgerService.record(LedgerEntry.builder()
                .entryUuid("cn-" + cn.getId() + "-taxrev")
                .bookingRef(bookingRef)
                .invoiceId(invoice != null ? invoice.getId() : null)
                .creditNoteId(cn.getId())
                .bingeId(booking.getBingeId())
                .customerId(booking.getCustomerId())
                .entryType(LedgerEntry.EntryType.TAX_REVERSAL)
                .direction(LedgerEntry.Direction.DEBIT)
                .amount(q.reversedTax())
                .currencyCode(q.currencyCode())
                .recordedBy(createdBy)
                .build());
        }
        if (q.cancellationFee().signum() > 0) {
            ledgerService.record(LedgerEntry.builder()
                .entryUuid("cn-" + cn.getId() + "-fee")
                .bookingRef(bookingRef)
                .invoiceId(invoice != null ? invoice.getId() : null)
                .creditNoteId(cn.getId())
                .bingeId(booking.getBingeId())
                .customerId(booking.getCustomerId())
                .entryType(LedgerEntry.EntryType.CANCELLATION_FEE)
                .direction(LedgerEntry.Direction.CREDIT)
                .amount(q.cancellationFee())
                .currencyCode(q.currencyCode())
                .recordedBy(createdBy)
                .build());
        }
        log.info("Credit note {} issued for booking {} amount={} tax={} fee={}",
            cn.getCreditNoteNumber(), bookingRef, cn.getAmount(), cn.getTaxAmount(), q.cancellationFee());
        return cn;
    }

    private BookingPriceSnapshot latestSnapshot(String bookingRef) {
        return snapshotRepo.findByBookingRefOrderByCreatedAtDesc(bookingRef).stream()
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException(
                "BookingPriceSnapshot", "bookingRef", bookingRef));
    }

    private String nextNumber() {
        return CN_PREFIX + "-" + LocalDateTime.now().format(YEAR) + "-" +
            String.format("%07d", COUNTER.incrementAndGet()) + "-" +
            UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
