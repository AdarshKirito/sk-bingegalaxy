package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.SystemSettingsService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.dto.PagedResponse;
import com.skbingegalaxy.common.enums.BookingStatus;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/bookings/admin")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AdminBookingController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final BookingService bookingService;
    private final SystemSettingsService systemSettingsService;
    private final BookingEventLogService eventLogService;
    private final com.skbingegalaxy.booking.service.BookingProjectionService projectionService;
    private final com.skbingegalaxy.booking.service.SagaOrchestrator sagaOrchestrator;
    private final com.skbingegalaxy.booking.service.PricingService pricingService;
    private final com.skbingegalaxy.booking.service.IdempotencyService idempotencyService;

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "bookingDate", "startTime", "status", "totalAmount",
            "customerName", "bookingRef", "paymentStatus", "updatedAt");
    private static final int MAX_SEARCH_QUERY_LENGTH = 100;

    private static final Set<String> BINGE_OPTIONAL_SUFFIXES = Set.of(
            "/dashboard-stats", "/operational-date");

    /** Returns true for endpoints that are accessible without a mandatory binge selection. */
    private static boolean isBingeOptional(String uri) {
        if (BINGE_OPTIONAL_SUFFIXES.stream().anyMatch(uri::endsWith)) return true;
        // Cross-customer review aggregation endpoints work across all binges
        return uri.contains("/admin/customers/")
            && (uri.endsWith("/reviews") || uri.endsWith("/review-summary"));
    }

    @ModelAttribute
    void validateManagedBinge(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (isBingeOptional(uri)) {
            // These endpoints allow null bingeId (SUPER_ADMIN cross-binge view),
            // but if a bingeId IS provided, the admin must own it.
            Long bingeId = BingeContext.getBingeId();
            if (bingeId != null) {
                adminBingeScopeService.requireManagedBinge(adminId, role, "accessing " + uri);
            } else if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
                // Only SUPER_ADMIN may view cross-binge (null bingeId) stats
                throw new com.skbingegalaxy.common.exception.BusinessException(
                    "Select a binge before accessing " + uri, HttpStatus.BAD_REQUEST);
            }
            return;
        }
        adminBingeScopeService.requireManagedBinge(adminId, role, "managing bookings");
    }

    private void requireSuperAdmin(String role, String action) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only super admins can " + action,
                HttpStatus.FORBIDDEN);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getAllBookings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        size = Math.min(size, MAX_PAGE_SIZE);
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Invalid sort field: " + sortBy + ". Allowed: " + ALLOWED_SORT_FIELDS);
        }
        Sort sort = direction.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Page<BookingDto> result = bookingService.getAllBookings(PageRequest.of(page, size, sort));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getTodayBookings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        size = Math.min(size, MAX_PAGE_SIZE);
        Page<BookingDto> result = bookingService.getTodayBookings(clientDate,
            PageRequest.of(page, size, Sort.by("startTime").ascending()));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getUpcomingBookings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        size = Math.min(size, MAX_PAGE_SIZE);
        Page<BookingDto> result = bookingService.getUpcomingBookings(clientDate,
            PageRequest.of(page, size, Sort.by("bookingDate").ascending().and(Sort.by("startTime").ascending())));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/by-date")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getBookingsByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, MAX_PAGE_SIZE);
        Page<BookingDto> result = bookingService.getBookingsByDate(date,
            PageRequest.of(page, size, Sort.by("startTime").ascending()));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/by-status")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getBookingsByStatus(
            @RequestParam String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        size = Math.min(size, MAX_PAGE_SIZE);
        BookingStatus bookingStatus;
        try {
            bookingStatus = BookingStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.skbingegalaxy.common.exception.BusinessException("Invalid booking status: " + status);
        }
        Page<BookingDto> result = bookingService.getBookingsByStatusForToday(
            bookingStatus, clientDate,
            PageRequest.of(page, size, Sort.by("startTime").ascending()));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> searchBookings(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        if (q.length() > MAX_SEARCH_QUERY_LENGTH) {
            q = q.substring(0, MAX_SEARCH_QUERY_LENGTH);
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        Page<BookingDto> result = bookingService.searchBookingsForToday(q, clientDate, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @PatchMapping("/{bookingRef}")
    public ResponseEntity<ApiResponse<BookingDto>> updateBooking(
            @PathVariable String bookingRef,
            @Valid @RequestBody UpdateBookingRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto updated = idempotencyService.execute(
            idempotencyKey, "PATCH", "/api/v1/bookings/admin/" + bookingRef, adminId,
            request, BookingDto.class,
            () -> bookingService.updateBooking(bookingRef, request));
        return ResponseEntity.ok(ApiResponse.ok("Booking updated", updated));
    }

    @PostMapping("/{bookingRef}/cancel")
    public ResponseEntity<ApiResponse<BookingDto>> cancelBooking(
            @PathVariable String bookingRef,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Body is optional for backward compatibility; the support console
        // posts {"reason": "..."} so the cancellation is self-documenting in
        // the audit log and surfaced on the booking detail panel.
        final String reason = body != null ? body.get("reason") : null;
        BookingDto cancelled = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/cancel", adminId,
            java.util.Map.of("bookingRef", bookingRef, "reason", reason == null ? "" : reason),
            BookingDto.class,
            () -> bookingService.cancelBooking(bookingRef, reason));
        return ResponseEntity.ok(ApiResponse.ok("Booking cancelled", cancelled));
    }

    @PostMapping("/{bookingRef}/confirm")
    public ResponseEntity<ApiResponse<BookingDto>> confirmBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UpdateBookingRequest req = new UpdateBookingRequest();
        req.setStatus("CONFIRMED");
        BookingDto confirmed = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/confirm", adminId,
            java.util.Map.of("bookingRef", bookingRef), BookingDto.class,
            () -> bookingService.updateBooking(bookingRef, req));
        return ResponseEntity.ok(ApiResponse.ok("Booking confirmed", confirmed));
    }

    @PostMapping("/{bookingRef}/check-in")
    public ResponseEntity<ApiResponse<BookingDto>> checkIn(
            @PathVariable String bookingRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/check-in", adminId,
            java.util.Map.of("bookingRef", bookingRef, "clientDate", String.valueOf(clientDate)),
            BookingDto.class,
            () -> {
                var booking = bookingService.getBookingEntity(bookingRef);
                LocalDate opDate = systemSettingsService.getOperationalDate(BingeContext.getBingeId(), clientDate);
                if (!booking.getBookingDate().equals(opDate)) {
                    throw new com.skbingegalaxy.common.exception.BusinessException(
                        "Can only check in bookings for the current operational day (" + opDate + "). This booking is for " + booking.getBookingDate());
                }
                if (booking.getStatus() != com.skbingegalaxy.common.enums.BookingStatus.CONFIRMED) {
                    throw new com.skbingegalaxy.common.exception.BusinessException(
                        "Cannot check in — booking must be CONFIRMED first. Current status: " + booking.getStatus());
                }
                var ps = booking.getPaymentStatus();
                if (ps == com.skbingegalaxy.common.enums.PaymentStatus.PENDING
                        || ps == com.skbingegalaxy.common.enums.PaymentStatus.FAILED
                        || ps == com.skbingegalaxy.common.enums.PaymentStatus.INITIATED) {
                    log.warn("Check-in for {} with paymentStatus={} — payment not yet collected",
                        bookingRef, ps);
                }
                UpdateBookingRequest req = new UpdateBookingRequest();
                req.setCheckedIn(true);
                return bookingService.updateBooking(bookingRef, req);
            });
        return ResponseEntity.ok(ApiResponse.ok("Check-in recorded", result));
    }

    @PostMapping("/{bookingRef}/checkout")
    public ResponseEntity<ApiResponse<BookingDto>> checkout(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestParam(required = false) String clientTime,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/checkout", adminId,
            java.util.Map.of("bookingRef", bookingRef,
                              "clientDate", String.valueOf(clientDate),
                              "clientTime", String.valueOf(clientTime)),
            BookingDto.class,
            () -> {
                var booking = bookingService.getBookingEntity(bookingRef);
                LocalDate opDate = systemSettingsService.getOperationalDate(BingeContext.getBingeId(), clientDate);
                if (!booking.getBookingDate().equals(opDate)) {
                    throw new com.skbingegalaxy.common.exception.BusinessException(
                        "Can only checkout bookings for the current operational day (" + opDate + "). This booking is for " + booking.getBookingDate());
                }
                LocalDateTime clientNow = null;
                if (clientDate != null && clientTime != null) {
                    try { clientNow = LocalDateTime.of(clientDate, LocalTime.parse(clientTime)); } catch (Exception e) {
                        log.warn("Failed to parse clientTime '{}' for checkout: {}", clientTime, e.getMessage());
                    }
                }
                return bookingService.earlyCheckout(bookingRef, clientNow);
            });
        return ResponseEntity.ok(ApiResponse.ok("Checkout completed", result));
    }

    @PostMapping("/{bookingRef}/undo-check-in")
    public ResponseEntity<ApiResponse<BookingDto>> undoCheckIn(
            @PathVariable String bookingRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Body is optional for backward compatibility; the support console
        // posts {"reason": "..."} so the reversal is self-documenting in the
        // audit timeline (CHECK_IN_REVERTED).
        final String reason = body != null ? body.get("reason") : null;
        BookingDto result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/undo-check-in", adminId,
            java.util.Map.of(
                "bookingRef", bookingRef,
                "clientDate", String.valueOf(clientDate),
                "reason", reason == null ? "" : reason),
            BookingDto.class,
            () -> {
                // Operational-day guard — admins shouldn't be reversing
                // yesterday's check-ins from today's console (those are
                // closed by the nightly audit job).
                var booking = bookingService.getBookingEntity(bookingRef);
                LocalDate opDate = systemSettingsService.getOperationalDate(BingeContext.getBingeId(), clientDate);
                if (!booking.getBookingDate().equals(opDate)) {
                    throw new com.skbingegalaxy.common.exception.BusinessException(
                        "Can only undo check-in for bookings on the current operational day ("
                            + opDate + ").");
                }
                return bookingService.undoCheckIn(bookingRef, adminId, reason);
            });
        return ResponseEntity.ok(ApiResponse.ok("Check-in undone", result));
    }

    @GetMapping("/dashboard-stats")
    public ResponseEntity<ApiResponse<DashboardStatsDto>> getDashboardStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getDashboardStats(clientDate)));
    }

    // ── Operational date ─────────────────────────────────────
    @GetMapping("/operational-date")
    public ResponseEntity<ApiResponse<OperationalDateDto>> getOperationalDate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestParam(required = false) String clientTime) {
        Long bid = BingeContext.getBingeId();
        LocalDate opDate = systemSettingsService.getOperationalDate(bid, clientDate);
        // Build client "now"; fall back to server clock if not provided
        LocalDateTime clientNow = null;
        if (clientDate != null && clientTime != null) {
            try {
                clientNow = LocalDateTime.of(clientDate, LocalTime.parse(clientTime));
            } catch (Exception e) {
                log.warn("Failed to parse clientTime '{}' for operational-date: {}", clientTime, e.getMessage());
            }
        }
        if (clientNow == null) clientNow = LocalDateTime.now();
        // Audit is available at or after 23:59 of the operational date (client local time)
        boolean available = !opDate.isAfter(clientDate != null ? clientDate : LocalDate.now())
            && clientNow.isAfter(opDate.atTime(LocalTime.of(23, 59)).minusSeconds(1));
        String reason = null;
        if (!available) {
            LocalDate refToday = clientDate != null ? clientDate : LocalDate.now();
            if (opDate.isAfter(refToday)) {
                reason = "Operational date is already ahead of today.";
            } else {
                String hhmm = String.format("%02d:%02d", clientNow.getHour(), clientNow.getMinute());
                reason = "Audit available after 11:59 PM. Your time: " + hhmm;
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(OperationalDateDto.builder()
            .operationalDate(opDate)
            .serverDateTime(clientNow)
            .auditAvailable(available)
            .auditUnavailableReason(reason)
            .build()));
    }

    // ── Operational date: SUPER_ADMIN manual control ─────────
    //
    // These endpoints exist so a venue super-admin can recover from clock
    // drift, cross a midnight boundary the audit window missed, or roll
    // forward in non-prod environments without waiting for the 23:59 audit
    // gate. Both endpoints require an explicitly-selected binge (multi-tenant
    // safety) and SUPER_ADMIN role.

    @PostMapping("/operational-date/advance")
    public ResponseEntity<ApiResponse<OperationalDateDto>> advanceOperationalDate(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        requireSuperAdmin(role, "advance the operational date");
        Long bid = BingeContext.getBingeId();
        if (bid == null) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Select a venue before advancing its operational date.", HttpStatus.BAD_REQUEST);
        }
        LocalDate newOp = systemSettingsService.advanceOperationalDate(bid, clientDate);
        log.info("[ops-audit] SUPER_ADMIN advanced operational date for binge {} to {}", bid, newOp);
        return ResponseEntity.ok(ApiResponse.ok("Operational date advanced",
            OperationalDateDto.builder()
                .operationalDate(newOp)
                .serverDateTime(LocalDateTime.now())
                .auditAvailable(false)
                .auditUnavailableReason(null)
                .build()));
    }

    @PostMapping("/operational-date/set")
    public ResponseEntity<ApiResponse<OperationalDateDto>> setOperationalDate(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @Valid @RequestBody SetOperationalDateRequest request) {
        requireSuperAdmin(role, "override the operational date");
        Long bid = BingeContext.getBingeId();
        if (bid == null) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Select a venue before overriding its operational date.", HttpStatus.BAD_REQUEST);
        }
        LocalDate newOp = systemSettingsService.setOperationalDate(
            bid, request.getOperationalDate(), clientDate);
        return ResponseEntity.ok(ApiResponse.ok("Operational date updated",
            OperationalDateDto.builder()
                .operationalDate(newOp)
                .serverDateTime(LocalDateTime.now())
                .auditAvailable(false)
                .auditUnavailableReason(null)
                .build()));
    }

    // ── Event Type management ────────────────────────────────

    @GetMapping("/event-types")
    public ResponseEntity<ApiResponse<java.util.List<EventTypeDto>>> getAllEventTypes() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getAllEventTypes()));
    }

    @PostMapping("/event-types")
    public ResponseEntity<ApiResponse<EventTypeDto>> createEventType(@Valid @RequestBody EventTypeSaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Event type created", bookingService.createEventType(req)));
    }

    @PutMapping("/event-types/{id}")
    public ResponseEntity<ApiResponse<EventTypeDto>> updateEventType(
            @PathVariable Long id, @Valid @RequestBody EventTypeSaveRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Event type updated", bookingService.updateEventType(id, req)));
    }

    @PatchMapping("/event-types/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleEventType(@PathVariable Long id) {
        bookingService.deactivateEventType(id);
        return ResponseEntity.ok(ApiResponse.ok("Event type toggled", null));
    }

    @DeleteMapping("/event-types/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEventType(@PathVariable Long id) {
        bookingService.deleteEventType(id);
        return ResponseEntity.ok(ApiResponse.ok("Event type deleted", null));
    }

    // ── Add-On management ────────────────────────────────────

    @GetMapping("/add-ons")
    public ResponseEntity<ApiResponse<java.util.List<AddOnDto>>> getAllAddOns() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getAllAddOns()));
    }

    @PostMapping("/add-ons")
    public ResponseEntity<ApiResponse<AddOnDto>> createAddOn(@Valid @RequestBody AddOnSaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Add-on created", bookingService.createAddOn(req)));
    }

    @PutMapping("/add-ons/{id}")
    public ResponseEntity<ApiResponse<AddOnDto>> updateAddOn(
            @PathVariable Long id, @Valid @RequestBody AddOnSaveRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Add-on updated", bookingService.updateAddOn(id, req)));
    }

    @PatchMapping("/add-ons/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleAddOn(@PathVariable Long id) {
        bookingService.deactivateAddOn(id);
        return ResponseEntity.ok(ApiResponse.ok("Add-on toggled", null));
    }

    @DeleteMapping("/add-ons/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAddOn(@PathVariable Long id) {
        bookingService.deleteAddOn(id);
        return ResponseEntity.ok(ApiResponse.ok("Add-on deleted", null));
    }

    // ── Reports ──────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<ReportDto>> getReport(
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getReport(period, clientDate)));
    }

    @GetMapping("/reports/date-range")
    public ResponseEntity<ApiResponse<ReportDto>> getReportByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getReportByDateRange(from, to, clientDate)));
    }

    // ── Audit: auto-resolve past bookings ────────────────────

    @PostMapping("/audit")
    public ResponseEntity<ApiResponse<AuditResultDto>> runAudit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestParam(required = false) String clientTime) {
        LocalDateTime clientNow = null;
        if (clientDate != null && clientTime != null) {
            try { clientNow = LocalDateTime.of(clientDate, LocalTime.parse(clientTime)); } catch (Exception e) {
                log.warn("Failed to parse clientTime '{}' for audit: {}", clientTime, e.getMessage());
            }
        }
        return ResponseEntity.ok(ApiResponse.ok("Audit completed",
            bookingService.runAudit(clientDate, clientNow)));
    }

    // ── House Accounts: pending payment bookings ─────────────

    @GetMapping("/house-accounts")
    public ResponseEntity<ApiResponse<PagedResponse<BookingDto>>> getHouseAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BookingDto> result = bookingService.getPendingPaymentBookings(
            PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    // ── Admin: create booking (walk-in) ──────────────────────

    @PostMapping("/create-booking")
    public ResponseEntity<ApiResponse<BookingDto>> adminCreateBooking(
            @Valid @RequestBody AdminCreateBookingRequest request,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto created = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/create-booking", adminId,
            request, BookingDto.class,
            () -> bookingService.adminCreateBooking(request));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Booking created by admin", created));
    }

    // ── Customer booking count ────────────────────────────────

    @GetMapping("/customer-booking-count/{customerId}")
    public ResponseEntity<ApiResponse<Long>> getCustomerBookingCount(@PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerBookingCount(customerId)));
    }

    // ── Customer review summary (avg admin rating + counts) ──

    @GetMapping("/customers/{customerId}/review-summary")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getCustomerReviewSummary(
            @PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerReviewSummary(customerId)));
    }

    // ── Customer admin reviews (paginated, most-recent first) ─

    @GetMapping("/customers/{customerId}/reviews")
    public ResponseEntity<ApiResponse<PagedResponse<BookingReviewDto>>> getCustomerAdminReviews(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        size = Math.min(size, MAX_PAGE_SIZE);
        org.springframework.data.domain.Page<BookingReviewDto> result =
            bookingService.getAdminReviewsForCustomer(
                customerId,
                PageRequest.of(page, size,
                    Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    // ── Booked slots for a date (double-booking prevention) ──

    @GetMapping("/booked-slots")
    public ResponseEntity<ApiResponse<java.util.List<BookedSlotDto>>> getBookedSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookedSlotsForDate(date)));
    }

    // ── Event history (audit trail) ──────────────────────────

    @GetMapping("/{bookingRef}/events")
    public ResponseEntity<ApiResponse<PagedResponse<BookingEventLogDto>>> getBookingEvents(
            @PathVariable String bookingRef,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        bookingService.getBookingEntity(bookingRef);
        Page<BookingEventLogDto> events = eventLogService
            .getEventHistory(bookingRef, PageRequest.of(page, size))
            .map(e -> BookingEventLogDto.builder()
                .id(e.getId())
                .bookingRef(e.getBookingRef())
                .eventType(e.getEventType().name())
                .previousStatus(e.getPreviousStatus())
                .newStatus(e.getNewStatus())
                .triggeredBy(e.getTriggeredBy())
                .triggeredByRole(e.getTriggeredByRole())
                .triggeredByName(e.getTriggeredByName())
                .description(e.getDescription())
                .snapshot(e.getSnapshot())
                .reason(e.getReason())
                .ipAddress(e.getIpAddress())
                .userAgent(e.getUserAgent())
                .bingeId(e.getBingeId())
                .eventVersion(e.getEventVersion())
                .createdAt(e.getCreatedAt())
                .build());
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(events)));
    }

    // ── CQRS projection replay ─────────────────────────────

    @PostMapping("/{bookingRef}/replay")
    public ResponseEntity<ApiResponse<String>> replayBooking(@PathVariable String bookingRef) {
        bookingService.getBookingEntity(bookingRef);
        projectionService.replayBooking(bookingRef);
        return ResponseEntity.ok(ApiResponse.ok("Projection rebuilt for " + bookingRef));
    }

    @PostMapping("/replay-all")
    public ResponseEntity<ApiResponse<String>> replayAll(
            @RequestHeader("X-User-Role") String role) {
        requireSuperAdmin(role, "replay all bookings");
        int count = projectionService.replayAll();
        return ResponseEntity.ok(ApiResponse.ok("Projection rebuilt for " + count + " bookings"));
    }

    // ── SUPER_ADMIN: state-machine override ──────────────────────────
    /**
     * Force a booking into a target status, bypassing the normal transition
     * table. Restricted to SUPER_ADMIN; reason is mandatory and is captured
     * in the audit trail tagged {@code MANUAL_REVIEW_FLAGGED}.
     *
     * <p>Allowed transitions (see {@code BookingStateMachine.OVERRIDE_TARGETS}):
     * <ul>
     *   <li>CANCELLED → CONFIRMED | PENDING (reinstate wrongly-cancelled)</li>
     *   <li>NO_SHOW → CHECKED_IN | CONFIRMED (undo misapplied no-show)</li>
     *   <li>COMPLETED → CHECKED_IN (revert premature checkout)</li>
     * </ul>
     *
     * <p>Idempotency-Key supported so retries don't double-apply the override.
     */
    @PostMapping("/{bookingRef}/override-status")
    public ResponseEntity<ApiResponse<BookingDto>> overrideStatus(
            @PathVariable String bookingRef,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-User-Id", required = false) Long superAdminId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireSuperAdmin(role, "override booking status");
        if (body == null || body.get("targetStatus") == null || body.get("targetStatus").isBlank()) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "targetStatus is required", HttpStatus.BAD_REQUEST);
        }
        if (body.get("reason") == null || body.get("reason").isBlank()) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "reason is required for status override", HttpStatus.BAD_REQUEST);
        }
        final BookingStatus target;
        try {
            target = BookingStatus.valueOf(body.get("targetStatus").trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Invalid target status: " + body.get("targetStatus"), HttpStatus.BAD_REQUEST);
        }
        final String reason = body.get("reason").trim();
        BookingDto result = idempotencyService.execute(
            idempotencyKey, "POST",
            "/api/v1/bookings/admin/" + bookingRef + "/override-status",
            superAdminId,
            java.util.Map.of(
                "bookingRef", bookingRef,
                "targetStatus", target.name(),
                "reason", reason),
            BookingDto.class,
            () -> bookingService.adminOverrideStatus(bookingRef, target, superAdminId, reason));
        return ResponseEntity.ok(ApiResponse.ok(
            "Status overridden to " + target + " with audit trail", result));
    }

    // ── Saga monitoring ──────────────────────────────────────

    @GetMapping("/sagas/failed")
    public ResponseEntity<ApiResponse<java.util.List<com.skbingegalaxy.booking.entity.SagaState>>> getFailedSagas(
            @RequestHeader("X-User-Role") String role) {
        requireSuperAdmin(role, "view global saga failures");
        return ResponseEntity.ok(ApiResponse.ok(sagaOrchestrator.getFailedSagas()));
    }

    @GetMapping("/sagas/compensating")
    public ResponseEntity<ApiResponse<java.util.List<com.skbingegalaxy.booking.entity.SagaState>>> getCompensatingSagas(
            @RequestHeader("X-User-Role") String role) {
        requireSuperAdmin(role, "view global compensating sagas");
        return ResponseEntity.ok(ApiResponse.ok(sagaOrchestrator.getCompensatingSagas()));
    }

    private <T> PagedResponse<T> toPagedResponse(Page<T> page) {
        return PagedResponse.<T>builder()
            .content(page.getContent())
            .page(page.getNumber())
            .size(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .last(page.isLast())
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    //  VENUE ROOM MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/venue-rooms")
    public ResponseEntity<ApiResponse<java.util.List<VenueRoomDto>>> getAllVenueRooms() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getAllVenueRooms()));
    }

    @PostMapping("/venue-rooms")
    public ResponseEntity<ApiResponse<VenueRoomDto>> createVenueRoom(
            @Valid @RequestBody VenueRoomSaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Room created", bookingService.createVenueRoom(request)));
    }

    @PutMapping("/venue-rooms/{id}")
    public ResponseEntity<ApiResponse<VenueRoomDto>> updateVenueRoom(
            @PathVariable Long id, @Valid @RequestBody VenueRoomSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Room updated", bookingService.updateVenueRoom(id, request)));
    }

    @PatchMapping("/venue-rooms/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleVenueRoom(@PathVariable Long id) {
        bookingService.toggleVenueRoom(id);
        return ResponseEntity.ok(ApiResponse.ok("Room toggled", null));
    }

    @DeleteMapping("/venue-rooms/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteVenueRoom(@PathVariable Long id) {
        bookingService.deleteVenueRoom(id);
        return ResponseEntity.ok(ApiResponse.ok("Room deleted", null));
    }

    // ═══════════════════════════════════════════════════════════
    //  SURGE PRICING RULE MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/pricing/surge-rules")
    public ResponseEntity<ApiResponse<java.util.List<SurgePricingRuleDto>>> getSurgeRules() {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getSurgeRules()));
    }

    @PostMapping("/pricing/surge-rules")
    public ResponseEntity<ApiResponse<SurgePricingRuleDto>> createSurgeRule(
            @Valid @RequestBody SurgePricingRuleSaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Surge rule created", pricingService.createSurgeRule(request)));
    }

    @PutMapping("/pricing/surge-rules/{id}")
    public ResponseEntity<ApiResponse<SurgePricingRuleDto>> updateSurgeRule(
            @PathVariable Long id, @Valid @RequestBody SurgePricingRuleSaveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Surge rule updated", pricingService.updateSurgeRule(id, request)));
    }

    @PatchMapping("/pricing/surge-rules/{id}/toggle-active")
    public ResponseEntity<ApiResponse<Void>> toggleSurgeRule(@PathVariable Long id) {
        pricingService.toggleSurgeRule(id);
        return ResponseEntity.ok(ApiResponse.ok("Surge rule toggled", null));
    }

    @DeleteMapping("/pricing/surge-rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSurgeRule(@PathVariable Long id) {
        pricingService.deleteSurgeRule(id);
        return ResponseEntity.ok(ApiResponse.ok("Surge rule deleted", null));
    }

    // ═══════════════════════════════════════════════════════════
    //  LOYALTY MANAGEMENT
    // ═══════════════════════════════════════════════════════════

}
