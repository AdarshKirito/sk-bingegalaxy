package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Server-side CSV export endpoint for admin bulk downloads.
 *
 * <p>Uses {@link StreamingResponseBody} to write rows directly to the HTTP output stream,
 * avoiding loading the entire dataset into memory — safe even for 100k+ rows.</p>
 *
 * <p>Requires the requesting admin to own (or be SUPER_ADMIN for) the selected binge
 * via the standard {@link AdminBingeScopeService#requireManagedBinge} check.</p>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/export")
@RequiredArgsConstructor
@Slf4j
public class ExportController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String CSV_HEADER = "Booking Ref,Customer,Event Type,Date,Start Time,Duration (min),Guests,Status,Payment Status,Total Amount,Collected,Created\r\n";
    private static final byte[] BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AdminBingeScopeService adminBingeScopeService;
    private final BookingService bookingService;

    /**
     * Verify the admin owns the selected binge before every request in this controller.
     */
    @ModelAttribute
    void validateManagedBinge(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            HttpServletRequest request) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "exporting bookings");
    }

    /**
     * Stream bookings for the current binge as CSV.
     *
     * @param from start date (ISO), defaults to 3 months ago
     * @param to   end date (ISO), defaults to today
     */
    @GetMapping(value = "/csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {

        var binge = adminBingeScopeService.requireManagedBinge(adminId, role, "exporting bookings");
        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now().minusMonths(3);
        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now();
        if (fromDate.isAfter(toDate)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "From date (" + fromDate + ") must not be after to date (" + toDate + ").");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) > 366) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Export date range cannot exceed 1 year. Please narrow the range.");
        }

        String filename = "bookings-" + binge.getName().replaceAll("[^a-zA-Z0-9_-]", "_")
                + "-" + fromDate + "-to-" + toDate + ".csv";

        log.info("Admin {} exporting CSV for binge {} ({} to {})", adminId, binge.getId(), fromDate, toDate);

        StreamingResponseBody stream = outputStream -> {
            outputStream.write(BOM); // UTF-8 BOM for Excel compatibility
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            writer.write(CSV_HEADER);

            List<Booking> bookings = bookingService.getBookingsForExport(binge.getId(), fromDate, toDate);
            for (Booking b : bookings) {
                writer.write(quote(b.getBookingRef()));       writer.write(',');
                writer.write(quote(b.getCustomerName()));     writer.write(',');
                writer.write(quote(b.getEventType() != null ? b.getEventType().getName() : "")); writer.write(',');
                writer.write(quote(b.getBookingDate() != null ? b.getBookingDate().format(DATE_FMT) : "")); writer.write(',');
                writer.write(quote(b.getStartTime() != null ? b.getStartTime().toString() : "")); writer.write(',');
                writer.write(String.valueOf(resolveDurationMinutes(b))); writer.write(',');
                writer.write(String.valueOf(b.getNumberOfGuests())); writer.write(',');
                writer.write(quote(b.getStatus().name()));    writer.write(',');
                writer.write(quote(b.getPaymentStatus() != null ? b.getPaymentStatus().name() : "PENDING")); writer.write(',');
                writer.write(formatDecimal(b.getTotalAmount()));    writer.write(',');
                writer.write(formatDecimal(b.getCollectedAmount()));writer.write(',');
                writer.write(quote(b.getCreatedAt() != null ? b.getCreatedAt().toString() : ""));
                writer.write("\r\n");
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(stream);
    }

    private String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String formatDecimal(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private int resolveDurationMinutes(Booking b) {
        if (b.getDurationMinutes() != null) return b.getDurationMinutes();
        return b.getDurationHours() * 60;
    }
}
