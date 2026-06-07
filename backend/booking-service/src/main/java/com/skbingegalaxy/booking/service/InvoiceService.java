package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.Invoice;
import com.skbingegalaxy.booking.entity.InvoiceLine;
import com.skbingegalaxy.booking.entity.LedgerEntry;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.InvoiceLineRepository;
import com.skbingegalaxy.booking.repository.InvoiceRepository;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issues invoices once a booking's payment is captured. Each invoice is
 * assigned a sequential, year-prefixed number that is unique within the
 * platform (e.g. {@code SKBG-INV-2025-000123}).
 *
 * <p>Invoice creation also writes paired ledger entries (CHARGE +
 * TAX_COLLECTED) via {@link LedgerService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class InvoiceService {

    private static final String INVOICE_PREFIX = "SKBG-INV";
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    /** In-memory increment used as a tie-breaker — DB unique constraint guarantees correctness. */
    private static final AtomicLong COUNTER = new AtomicLong(System.currentTimeMillis() % 100_000);

    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineRepository lineRepo;
    private final BookingRepository bookingRepo;
    private final LedgerService ledgerService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /** Generate (or return existing) invoice for a booking after payment SUCCESS. */
    public Invoice generate(String bookingRef) {
        Invoice existing = invoiceRepo.findFirstByBookingRefOrderByIdDesc(bookingRef).orElse(null);
        if (existing != null && existing.getStatus() != Invoice.Status.DRAFT) {
            return existing;
        }
        Booking booking = bookingRepo.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));

        Invoice invoice = Invoice.builder()
            .invoiceNumber(nextNumber())
            .bookingRef(bookingRef)
            .bingeId(booking.getBingeId())
            .customerId(booking.getCustomerId())
            .snapshotId(booking.getPriceSnapshotId())
            .billingAddressId(booking.getBillingAddressId())
            .currencyCode(firstNonBlank(booking.getDisplayCurrencyCode(), booking.getCurrencyCode(), "INR"))
            .subtotal(booking.getSubtotalAmount())
            .taxTotal(booking.getTaxAmount())
            .grandTotal(booking.getDisplayAmount() != null ? booking.getDisplayAmount() : booking.getTotalAmount())
            .status(Invoice.Status.ISSUED)
            .issuedAt(LocalDateTime.now(ZoneOffset.UTC))
            .taxBreakdownJson(booking.getTaxBreakdownJson())
            .build();
        invoice = invoiceRepo.save(invoice);

        // Lines
        List<InvoiceLine> lines = new ArrayList<>();
        if (booking.getBaseAmount() != null && booking.getBaseAmount().signum() > 0) {
            lines.add(InvoiceLine.builder()
                .invoiceId(invoice.getId())
                .lineType(InvoiceLine.LineType.CHARGE)
                .description("Booking " + bookingRef)
                .unitAmount(booking.getBaseAmount())
                .amount(booking.getBaseAmount())
                .sortOrder(10)
                .build());
        }
        if (booking.getAddOnAmount() != null && booking.getAddOnAmount().signum() > 0) {
            lines.add(InvoiceLine.builder()
                .invoiceId(invoice.getId())
                .lineType(InvoiceLine.LineType.ADDON)
                .description("Add-ons")
                .unitAmount(booking.getAddOnAmount())
                .amount(booking.getAddOnAmount())
                .sortOrder(20)
                .build());
        }
        if (booking.getGuestAmount() != null && booking.getGuestAmount().signum() > 0) {
            lines.add(InvoiceLine.builder()
                .invoiceId(invoice.getId())
                .lineType(InvoiceLine.LineType.GUEST_FEE)
                .description("Guest fee")
                .unitAmount(booking.getGuestAmount())
                .amount(booking.getGuestAmount())
                .sortOrder(30)
                .build());
        }
        if (booking.getTaxAmount() != null && booking.getTaxAmount().signum() > 0) {
            lines.add(InvoiceLine.builder()
                .invoiceId(invoice.getId())
                .lineType(InvoiceLine.LineType.TAX)
                .description("Tax")
                .unitAmount(booking.getTaxAmount())
                .amount(booking.getTaxAmount())
                .sortOrder(90)
                .build());
        }
        if (!lines.isEmpty()) lineRepo.saveAll(lines);

        // Ledger
        BigDecimal grand = invoice.getGrandTotal() != null ? invoice.getGrandTotal() : BigDecimal.ZERO;
        BigDecimal tax = invoice.getTaxTotal() != null ? invoice.getTaxTotal() : BigDecimal.ZERO;
        ledgerService.record(LedgerEntry.builder()
            .entryUuid("inv-" + invoice.getId() + "-charge")
            .bookingRef(bookingRef)
            .invoiceId(invoice.getId())
            .bingeId(booking.getBingeId())
            .customerId(booking.getCustomerId())
            .entryType(LedgerEntry.EntryType.CHARGE)
            .direction(LedgerEntry.Direction.CREDIT)
            .amount(grand)
            .currencyCode(invoice.getCurrencyCode())
            .build());
        if (tax.signum() > 0) {
            ledgerService.record(LedgerEntry.builder()
                .entryUuid("inv-" + invoice.getId() + "-tax")
                .bookingRef(bookingRef)
                .invoiceId(invoice.getId())
                .bingeId(booking.getBingeId())
                .customerId(booking.getCustomerId())
                .entryType(LedgerEntry.EntryType.TAX_COLLECTED)
                .direction(LedgerEntry.Direction.CREDIT)
                .amount(tax)
                .currencyCode(invoice.getCurrencyCode())
                .build());
        }

        log.info("Invoice {} issued for booking {}", invoice.getInvoiceNumber(), bookingRef);
        return invoice;
    }

    @Transactional(readOnly = true)
    public List<InvoiceLine> linesFor(Long invoiceId) {
        return lineRepo.findByInvoiceIdOrderBySortOrderAscIdAsc(invoiceId);
    }

    @Transactional(readOnly = true)
    public List<Invoice> listForCustomer(Long customerId) {
        return invoiceRepo.findByCustomerIdOrderByIssuedAtDesc(customerId);
    }

    /** Admin: list all invoices for a binge, newest first. */
    @Transactional(readOnly = true)
    public List<Invoice> listForBinge(Long bingeId) {
        return invoiceRepo.findByBingeIdOrderByIssuedAtDesc(bingeId);
    }

    /**
     * Admin: re-emit the invoice email for a booking. The PDF is regenerated
     * on the customer's next download — this just enqueues a notification
     * with a link/attachment hint.
     *
     * <p>Idempotent at the Kafka layer; safe to invoke multiple times.</p>
     *
     * @return the resolved Invoice, or throws if none exists for this booking.
     */
    public Invoice resend(String bookingRef) {
        Invoice invoice = invoiceRepo.findFirstByBookingRefOrderByIdDesc(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice", "bookingRef", bookingRef));
        Booking booking = bookingRepo.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));

        try {
            String subject = "Invoice " + invoice.getInvoiceNumber() + " for booking " + bookingRef;
            String body = String.format(
                "Hi %s,%n%nPlease find your invoice (%s) for booking %s." +
                "%nGrand total: %s %s.%n%nYou can download it from your bookings page.",
                booking.getCustomerName(),
                invoice.getInvoiceNumber(),
                bookingRef,
                invoice.getCurrencyCode(),
                invoice.getGrandTotal() != null ? invoice.getGrandTotal().toPlainString() : "0.00");

            NotificationEvent ev = NotificationEvent.builder()
                .type("INVOICE_RESEND")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(booking.getCustomerEmail())
                .recipientName(booking.getCustomerName())
                .recipientPhone(booking.getCustomerPhone())
                .recipientPhoneCountryCode(booking.getCustomerPhoneCountryCode())
                .subject(subject)
                .body(body)
                .bookingRef(bookingRef)
                .metadata(Map.of(
                    "invoiceNumber", invoice.getInvoiceNumber(),
                    "invoiceId", String.valueOf(invoice.getId())))
                .build();

            // Aggregate key includes a timestamp so multiple resends each
            // produce a distinct outbox row (unlike OTPs which are designed
            // to be deduped). The notification-service de-dupes by metadata
            // if the operator hammers the button.
            String aggKey = "INV-RESEND-" + bookingRef + "-" + System.currentTimeMillis();
            OutboxEvent outbox = OutboxEvent.builder()
                .topic(KafkaTopics.NOTIFICATION_SEND)
                .aggregateKey(aggKey)
                .payload(objectMapper.writeValueAsString(ev))
                .build();
            outboxEventRepository.save(outbox);
            log.info("Invoice {} resend queued for booking {}", invoice.getInvoiceNumber(), bookingRef);
        } catch (Exception e) {
            log.error("Failed to enqueue invoice resend for booking {}: {}", bookingRef, e.getMessage());
            throw new IllegalStateException("Failed to enqueue invoice resend", e);
        }
        return invoice;
    }

    private String nextNumber() {
        return INVOICE_PREFIX + "-" + LocalDateTime.now(ZoneOffset.UTC).format(YEAR) + "-" +
            String.format("%07d", COUNTER.incrementAndGet());
    }

    private static String firstNonBlank(String... opts) {
        for (String o : opts) if (o != null && !o.isBlank()) return o;
        return "";
    }
}
