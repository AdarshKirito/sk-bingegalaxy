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
    private final com.skbingegalaxy.booking.service.LoyaltyService loyaltyService;

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
            @Valid @RequestBody UpdateBookingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Booking updated", bookingService.updateBooking(bookingRef, request)));
    }

    @PostMapping("/{bookingRef}/cancel")
    public ResponseEntity<ApiResponse<BookingDto>> cancelBooking(@PathVariable String bookingRef) {
        return ResponseEntity.ok(ApiResponse.ok("Booking cancelled", bookingService.cancelBooking(bookingRef)));
    }

    @PostMapping("/{bookingRef}/confirm")
    public ResponseEntity<ApiResponse<BookingDto>> confirmBooking(@PathVariable String bookingRef) {
        UpdateBookingRequest req = new UpdateBookingRequest();
        req.setStatus("CONFIRMED");
        return ResponseEntity.ok(ApiResponse.ok("Booking confirmed", bookingService.updateBooking(bookingRef, req)));
    }

    @PostMapping("/{bookingRef}/check-in")
    public ResponseEntity<ApiResponse<BookingDto>> checkIn(
            @PathVariable String bookingRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
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
        // Warn (but allow) check-in when payment is not yet collected.
        // Admin may have legitimate reasons (VIP, external payment, etc.)
        var ps = booking.getPaymentStatus();
        if (ps == com.skbingegalaxy.common.enums.PaymentStatus.PENDING
                || ps == com.skbingegalaxy.common.enums.PaymentStatus.FAILED
                || ps == com.skbingegalaxy.common.enums.PaymentStatus.INITIATED) {
            log.warn("Check-in for {} with paymentStatus={} — payment not yet collected",
                bookingRef, ps);
        }
        UpdateBookingRequest req = new UpdateBookingRequest();
        req.setCheckedIn(true);
        return ResponseEntity.ok(ApiResponse.ok("Check-in recorded", bookingService.updateBooking(bookingRef, req)));
    }

    @PostMapping("/{bookingRef}/checkout")
    public ResponseEntity<ApiResponse<BookingDto>> checkout(
            @PathVariable String bookingRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate,
            @RequestParam(required = false) String clientTime) {
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
        return ResponseEntity.ok(ApiResponse.ok("Checkout completed", bookingService.earlyCheckout(bookingRef, clientNow)));
    }

    @PostMapping("/{bookingRef}/undo-check-in")
    public ResponseEntity<ApiResponse<BookingDto>> undoCheckIn(
            @PathVariable String bookingRef,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        var booking = bookingService.getBookingEntity(bookingRef);
        LocalDate opDate = systemSettingsService.getOperationalDate(BingeContext.getBingeId(), clientDate);
        if (!booking.getBookingDate().equals(opDate)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Can only undo check-in for bookings on the current operational day (" + opDate + ").");
        }
        UpdateBookingRequest req = new UpdateBookingRequest();
        req.setStatus("CONFIRMED");
        req.setCheckedIn(false);
        return ResponseEntity.ok(ApiResponse.ok("Check-in undone", bookingService.updateBooking(bookingRef, req)));
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
            @Valid @RequestBody AdminCreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Booking created by admin", bookingService.adminCreateBooking(request)));
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
                .description(e.getDescription())
                .snapshot(e.getSnapshot())
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

    @GetMapping("/loyalty/{customerId}")
    public ResponseEntity<ApiResponse<LoyaltyAccountDto>> getCustomerLoyalty(
            @PathVariable Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(loyaltyService.getAccount(customerId)));
    }

    @PostMapping("/loyalty/{customerId}/adjust")
    public ResponseEntity<ApiResponse<LoyaltyAccountDto>> adjustLoyaltyPoints(
            @PathVariable Long customerId,
            @RequestBody java.util.Map<String, Object> body,
            @RequestHeader("X-User-Role") String role) {
        long points = ((Number) body.get("points")).longValue();
        String description = (String) body.getOrDefault("description", null);
        return ResponseEntity.ok(ApiResponse.ok("Points adjusted", loyaltyService.adjustPoints(customerId, points, description, role)));
    }
}
