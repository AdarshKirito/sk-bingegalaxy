package com.skbingegalaxy.booking.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.skbingegalaxy.booking.entity.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Generates branded PDF invoices for bookings.
 * Uses OpenPDF (LGPL fork of iText 4) for server-side PDF generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(99, 102, 241));
    private static final Font HEADING_FONT = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.GRAY);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private final BookingService bookingService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Generate a PDF invoice for the given booking reference.
     *
     * @return byte array containing the PDF document
     */
    public byte[] generateInvoice(String bookingRef) {
        Booking booking = bookingService.getBookingEntityForSystem(bookingRef);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            // Header
            Paragraph brand = new Paragraph("SK Binge Galaxy", TITLE_FONT);
            brand.setAlignment(Element.ALIGN_CENTER);
            doc.add(brand);

            Paragraph subtitle = new Paragraph("Booking Invoice", HEADING_FONT);
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(20);
            doc.add(subtitle);

            // Booking details table
            PdfPTable details = new PdfPTable(2);
            details.setWidthPercentage(100);
            details.setWidths(new float[]{1, 2});
            details.setSpacingAfter(15);

            addRow(details, "Booking Ref", booking.getBookingRef());
            addRow(details, "Status", booking.getStatus().name());

            if (booking.getCustomerName() != null) {
                addRow(details, "Customer", booking.getCustomerName());
            }
            if (booking.getCustomerEmail() != null) {
                addRow(details, "Email", booking.getCustomerEmail());
            }
            if (booking.getEventType() != null) {
                addRow(details, "Event Type", booking.getEventType().getName());
            }
            if (booking.getBookingDate() != null) {
                addRow(details, "Date", booking.getBookingDate().format(DATE_FMT));
            }
            if (booking.getStartTime() != null) {
                addRow(details, "Time", booking.getStartTime().format(TIME_FMT));
            }
            addRow(details, "Duration", resolveDuration(booking));
            addRow(details, "Guests", String.valueOf(booking.getNumberOfGuests()));

            doc.add(details);

            // Pricing section
            doc.add(new Paragraph("Pricing", HEADING_FONT));
            doc.add(Chunk.NEWLINE);

            PdfPTable pricing = new PdfPTable(2);
            pricing.setWidthPercentage(60);
            pricing.setHorizontalAlignment(Element.ALIGN_LEFT);
            pricing.setSpacingAfter(15);

            if (booking.getBaseAmount() != null && booking.getBaseAmount().signum() > 0) {
                addRow(pricing, "Base", formatMoney(booking, booking.getBaseAmount()));
            }
            if (booking.getAddOnAmount() != null && booking.getAddOnAmount().signum() > 0) {
                addRow(pricing, "Add-ons", formatMoney(booking, booking.getAddOnAmount()));
            }
            if (booking.getGuestAmount() != null && booking.getGuestAmount().signum() > 0) {
                addRow(pricing, "Guests", formatMoney(booking, booking.getGuestAmount()));
            }
            if (booking.getLoyaltyDiscountAmount() != null && booking.getLoyaltyDiscountAmount().signum() > 0) {
                addRow(pricing, "Loyalty discount", "- " + formatMoney(booking, booking.getLoyaltyDiscountAmount()));
            }
            if (booking.getSubtotalAmount() != null) {
                addRow(pricing, "Subtotal (pre-tax)", formatMoney(booking, booking.getSubtotalAmount()));
            }

            // Itemised taxes — one line per rule, so the guest sees WHAT each
            // charge is (e.g. "GST 18% — IN/KA", "Occupancy fee — flat × 2h").
            for (TaxLineView t : parseTaxLines(booking.getTaxBreakdownJson())) {
                addRow(pricing, "  " + t.label(), formatMoney(booking, t.amount()));
            }
            if (booking.getTaxAmount() != null && booking.getTaxAmount().signum() > 0) {
                addRow(pricing, "Total tax", formatMoney(booking, booking.getTaxAmount()));
            }

            addRow(pricing, "Total Amount", formatMoney(booking, booking.getTotalAmount()));
            addRow(pricing, "Collected", formatMoney(booking, booking.getCollectedAmount()));
            addRow(pricing, "Payment Status",
                    booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : "PENDING");

            doc.add(pricing);

            // Special notes
            if (booking.getSpecialNotes() != null && !booking.getSpecialNotes().isBlank()) {
                doc.add(new Paragraph("Special Notes", HEADING_FONT));
                doc.add(new Paragraph(sanitize(booking.getSpecialNotes()), NORMAL_FONT));
                doc.add(Chunk.NEWLINE);
            }

            // Footer
            Paragraph footer = new Paragraph(
                    "Generated by SK Binge Galaxy | This is a computer-generated document.",
                    SMALL_FONT);
            footer.setAlignment(Element.ALIGN_CENTER);
            footer.setSpacingBefore(30);
            doc.add(footer);

            doc.close();
        } catch (Exception e) {
            log.error("Failed to generate PDF invoice for booking {}", bookingRef, e);
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }

        return baos.toByteArray();
    }

    private void addRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, HEADING_FONT));
        labelCell.setBorder(0);
        labelCell.setPadding(4);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(sanitize(value), NORMAL_FONT));
        valueCell.setBorder(0);
        valueCell.setPadding(4);
        table.addCell(valueCell);
    }

    /**
     * Sanitize user-supplied text before inserting into the PDF.
     * Strips control characters and limits length to prevent injection or corrupt documents.
     */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "—";
        // Remove control chars except newline/tab, limit length
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return clean.length() > 500 ? clean.substring(0, 500) + "…" : clean;
    }

    /** Minimal view of one persisted tax line for invoice rendering. */
    private record TaxLineView(String label, BigDecimal amount) {}

    /**
     * Parse the persisted per-rule breakdown (JSON array of TaxLine) into
     * printable label/amount pairs. Defensive: legacy bookings may have a null
     * or malformed breakdown — the invoice then just shows the tax total.
     */
    private java.util.List<TaxLineView> parseTaxLines(String breakdownJson) {
        if (breakdownJson == null || breakdownJson.isBlank()) return java.util.List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(breakdownJson);
            if (!arr.isArray()) return java.util.List.of();
            java.util.List<TaxLineView> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String name = n.path("name").asText("Tax");
                String taxType = n.path("taxType").asText("");
                String jurisdiction = n.path("jurisdiction").asText("");
                String calcMethod = n.path("calcMethod").asText("");
                StringBuilder label = new StringBuilder(name);
                if ("FLAT_PER_HOUR".equals(calcMethod) && n.hasNonNull("units")) {
                    label.append(" (flat x ").append(n.path("units").asInt()).append("h)");
                } else if ("FLAT_PER_BOOKING".equals(calcMethod)) {
                    label.append(" (flat)");
                } else if (n.hasNonNull("rateBps")) {
                    label.append(" (").append(new BigDecimal(n.path("rateBps").asInt())
                        .divide(new BigDecimal(100)).stripTrailingZeros().toPlainString()).append("%)");
                }
                if (!taxType.isBlank() && !"GENERIC".equals(taxType)) label.append(" ").append(taxType);
                if (!jurisdiction.isBlank() && !"GLOBAL".equals(jurisdiction)) {
                    label.append(" - ").append(jurisdiction);
                }
                if (n.path("inclusive").asBoolean(false)) label.append(" [incl.]");
                out.add(new TaxLineView(label.toString(), n.path("amount").decimalValue()));
            }
            return out;
        } catch (Exception e) {
            log.debug("Unparseable tax breakdown on invoice: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Formats an amount in the BOOKING's own currency (native per-binge model).
     * Uses the ISO code rather than a symbol: the built-in Helvetica font is
     * Latin-1 only, so non-Latin symbols like ₹ cannot render in the PDF — the
     * old hardcoded "₹" printed as an empty box.
     */
    private String formatMoney(Booking booking, BigDecimal amount) {
        String code = booking.getPaymentCurrencyCode() != null
            ? booking.getPaymentCurrencyCode() : "INR";
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return code + " " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String resolveDuration(Booking booking) {
        int minutes = booking.getScheduledDurationMinutes(); // V82: single canonical accessor
        if (minutes % 60 == 0) return (minutes / 60) + " hour" + (minutes / 60 != 1 ? "s" : "");
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
