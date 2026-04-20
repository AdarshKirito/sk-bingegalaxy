package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.InvoicePdfService;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
