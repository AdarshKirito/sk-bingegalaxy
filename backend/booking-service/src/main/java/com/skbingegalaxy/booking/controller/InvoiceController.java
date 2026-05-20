package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.entity.Invoice;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.InvoicePdfService;
import com.skbingegalaxy.booking.service.InvoiceService;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Endpoint for downloading server-generated PDF invoices.
 *
 * <p>Customers may only download invoices for their own bookings.
 * Admins/SUPER_ADMINs may download any invoice.</p>
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
public class InvoiceController {

    private final InvoicePdfService invoicePdfService;
    private final BookingService bookingService;
    private final AdminBingeScopeService adminBingeScopeService;
    private final InvoiceService invoiceService;

    @GetMapping("/{ref}/invoice")
    public ResponseEntity<byte[]> downloadInvoice(
            @PathVariable String ref,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {

        // Ownership check: customers can only download their own booking invoices
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            BookingDto booking = bookingService.getByRef(ref);
            if (!userId.equals(booking.getCustomerId())) {
                throw new BusinessException("Not authorised to download this invoice", HttpStatus.FORBIDDEN);
            }
        } else {
            // Admin callers must own the binge the booking belongs to
            BookingDto booking = bookingService.getByRef(ref);
            adminBingeScopeService.requireBingeOwnership(booking.getBingeId(), userId, userRole, "downloading invoice");
        }

        byte[] pdf = invoicePdfService.generateInvoice(ref);
        log.info("Invoice downloaded for booking {} by user {} ({})", ref, userId, userRole);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice-" + ref + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    /**
     * Admin: list all invoices for the currently selected binge.
     * Gateway scopes this to admin via the {@code /admin/} path segment.
     */
    @GetMapping("/admin/invoices")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listInvoicesForBinge() {
        Long bingeId = adminBingeScopeService.requireSelectedBinge("listing invoices");
        List<Invoice> invoices = invoiceService.listForBinge(bingeId);
        List<Map<String, Object>> body = invoices.stream().map(this::summarise).toList();
        return ResponseEntity.ok(ApiResponse.ok("Invoices retrieved", body));
    }

    /**
     * Admin: re-emit the invoice email for a booking. Returns the invoice
     * number so the operator can confirm the resend hit the right document.
     */
    @PostMapping("/admin/{ref}/invoice/resend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendInvoice(
            @PathVariable String ref,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        BookingDto booking = bookingService.getByRef(ref);
        adminBingeScopeService.requireBingeOwnership(
            booking.getBingeId(), userId, userRole != null ? userRole : "ADMIN", "resending invoice");
        Invoice invoice = invoiceService.resend(ref);
        return ResponseEntity.ok(ApiResponse.ok(
            "Invoice resend queued",
            Map.of(
                "bookingRef", ref,
                "invoiceNumber", invoice.getInvoiceNumber(),
                "invoiceId", invoice.getId())));
    }

    private Map<String, Object> summarise(Invoice inv) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("id", inv.getId());
        m.put("invoiceNumber", inv.getInvoiceNumber());
        m.put("bookingRef", inv.getBookingRef());
        m.put("customerId", inv.getCustomerId());
        m.put("currencyCode", inv.getCurrencyCode());
        m.put("subtotal", nz(inv.getSubtotal()));
        m.put("taxTotal", nz(inv.getTaxTotal()));
        m.put("grandTotal", nz(inv.getGrandTotal()));
        m.put("status", inv.getStatus() != null ? inv.getStatus().name() : null);
        m.put("issuedAt", inv.getIssuedAt());
        m.put("createdAt", inv.getCreatedAt());
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
