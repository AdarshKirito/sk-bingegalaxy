package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.IdempotencyService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class BookingController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final BookingService bookingService;
    private final PricingService pricingService;
    private final IdempotencyService idempotencyService;

    @ModelAttribute
    void validateSelectedBinge() {
        adminBingeScopeService.requireSelectedBinge("accessing binge bookings");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDto>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Name", defaultValue = "Customer") String name,
            @RequestHeader(value = "X-User-Phone", defaultValue = "") String phone,
            @RequestHeader(value = "X-User-Phone-Country-Code", defaultValue = "") String phoneCountryCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Stripe-style idempotency: a client-supplied key turns retries/double-clicks
        // into a no-op returning the originally-created booking. Missing/blank key
        // keeps the legacy behaviour (useful for server-initiated calls and tests).
        BookingDto booking = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings", userId, request, BookingDto.class,
            () -> bookingService.createBooking(request, userId, name, email, phone, phoneCountryCode));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Booking created successfully", booking));
    }

    @GetMapping("/{bookingRef}")
    public ResponseEntity<ApiResponse<BookingDto>> getBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {
        BookingDto booking = bookingService.getByRef(bookingRef);
        // Ownership check: customers can only view their own bookings
        if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPER_ADMIN".equalsIgnoreCase(userRole)
                && !userId.equals(booking.getCustomerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Not authorized to view this booking"));
        }
        // Admin callers must own the binge this booking belongs to
        if ("ADMIN".equalsIgnoreCase(userRole) || "SUPER_ADMIN".equalsIgnoreCase(userRole)) {
            adminBingeScopeService.requireManagedBinge(userId, userRole, "viewing booking");
        }
        return ResponseEntity.ok(ApiResponse.ok(booking));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyBookings(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerBookings(userId)));
    }

    @GetMapping("/my/current")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyCurrentBookings(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerCurrentBookings(userId, clientDate)));
    }

    @GetMapping("/my/past")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyPastBookings(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerPastBookings(userId, clientDate)));
    }

    @GetMapping("/my/reviews/pending")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyPendingReviews(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getPendingCustomerReviews(userId, clientDate)));
    }

    @GetMapping("/event-types")
    public ResponseEntity<ApiResponse<List<EventTypeDto>>> getEventTypes() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getActiveEventTypes()));
    }

    @GetMapping("/add-ons")
    public ResponseEntity<ApiResponse<List<AddOnDto>>> getAddOns() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getActiveAddOns()));
    }

    @GetMapping("/booked-slots")
    public ResponseEntity<ApiResponse<List<BookedSlotDto>>> getBookedSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookedSlotsForDate(date)));
    }

    @GetMapping("/slot-capacity")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getSlotCapacity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam int startMinute,
            @RequestParam int durationMinutes) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getSlotCapacity(date, startMinute, durationMinutes)));
    }

    @PostMapping("/{bookingRef}/cancel")
    public ResponseEntity<ApiResponse<BookingDto>> cancelMyBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto cancelled = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/" + bookingRef + "/cancel", userId,
            java.util.Map.of("bookingRef", bookingRef), BookingDto.class,
            () -> bookingService.cancelBookingByCustomer(bookingRef, userId));
        return ResponseEntity.ok(ApiResponse.ok("Booking cancelled", cancelled));
    }

    @PostMapping("/{bookingRef}/reschedule")
    public ResponseEntity<ApiResponse<BookingDto>> rescheduleMyBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody RescheduleBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Note: @Valid intentionally omitted. The service performs ownership check
        // FIRST and then validates body fields, so probing for resources you don't
        // own returns 403 instead of leaking field-shape via 400.
        BookingDto rescheduled = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/" + bookingRef + "/reschedule", userId,
            request, BookingDto.class,
            () -> bookingService.rescheduleBooking(bookingRef, userId, request));
        return ResponseEntity.ok(ApiResponse.ok("Booking rescheduled successfully", rescheduled));
    }

    @PostMapping("/{bookingRef}/transfer")
    public ResponseEntity<ApiResponse<BookingDto>> transferMyBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody TransferBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingDto transferred = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/" + bookingRef + "/transfer", userId,
            request, BookingDto.class,
            () -> bookingService.transferBooking(bookingRef, userId, request));
        return ResponseEntity.ok(ApiResponse.ok("Booking transferred successfully", transferred));
    }

    @PostMapping("/recurring")
    public ResponseEntity<ApiResponse<RecurringBookingResult>> createRecurringBookings(
            @Valid @RequestBody RecurringBookingRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Name", defaultValue = "Customer") String name,
            @RequestHeader(value = "X-User-Phone", defaultValue = "") String phone,
            @RequestHeader(value = "X-User-Phone-Country-Code", defaultValue = "") String phoneCountryCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RecurringBookingResult result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/recurring", userId,
            request, RecurringBookingResult.class,
            () -> bookingService.createRecurringBookings(request, userId, name, email, phone, phoneCountryCode));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Recurring bookings created", result));
    }

    @GetMapping("/recurring/{groupId}")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getRecurringGroup(
            @PathVariable String groupId,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getRecurringGroupBookings(groupId, userId)));
    }

    @GetMapping("/{bookingRef}/reviews/customer")
    public ResponseEntity<ApiResponse<BookingReviewDto>> getMyReview(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerReview(bookingRef, userId)));
    }

    @PostMapping("/{bookingRef}/reviews/customer")
    public ResponseEntity<ApiResponse<BookingReviewDto>> submitMyReview(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CustomerReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Review submitted", bookingService.submitCustomerReview(bookingRef, userId, request)));
    }

    @PostMapping("/admin/{bookingRef}/reviews")
    public ResponseEntity<ApiResponse<BookingReviewDto>> submitAdminReview(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody AdminReviewRequest request) {
        adminBingeScopeService.requireManagedBinge(userId, role, "submitting admin review");
        return ResponseEntity.ok(ApiResponse.ok("Admin review submitted", bookingService.submitAdminReview(bookingRef, userId, role, request)));
    }

    @GetMapping("/admin/{bookingRef}/reviews")
    public ResponseEntity<ApiResponse<List<BookingReviewDto>>> getAdminReviews(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        adminBingeScopeService.requireManagedBinge(userId, role, "viewing admin reviews");
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getAdminReviewsForBooking(bookingRef, role)));
    }

    @GetMapping("/my-pricing")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> getMyPricing(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveCustomerPricing(userId)));
    }

    // ── Venue Rooms ──────────────────────────────────────────

    @GetMapping("/venue-rooms")
    public ResponseEntity<ApiResponse<java.util.List<VenueRoomDto>>> getActiveRooms() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getActiveVenueRooms()));
    }

    @GetMapping("/venue-rooms/available")
    public ResponseEntity<ApiResponse<java.util.List<VenueRoomDto>>> getAvailableRooms(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam int startMinute,
            @RequestParam int durationMinutes) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getAvailableRooms(date, startMinute, durationMinutes)));
    }

    // ── Surge Pricing (public read) ──────────────────────────

    @GetMapping("/surge-rules")
    public ResponseEntity<ApiResponse<java.util.List<SurgePricingRuleDto>>> getActiveSurgeRules() {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.getActiveSurgeRules()));
    }

    // ── Customer-facing timeline ─────────────────────────────
    // Returns a curated milestone list — only events the customer should see,
    // and with admin-only fields stripped (snapshot, IP, User-Agent, internal
    // actor IDs). Mirrors the admin /events endpoint but is privacy-aware.

    private static final java.util.Set<com.skbingegalaxy.booking.entity.BookingEventType>
        CUSTOMER_VISIBLE_EVENTS = java.util.EnumSet.of(
            com.skbingegalaxy.booking.entity.BookingEventType.SLOT_HELD,
            com.skbingegalaxy.booking.entity.BookingEventType.CREATED,
            com.skbingegalaxy.booking.entity.BookingEventType.PAYMENT_INITIATED,
            com.skbingegalaxy.booking.entity.BookingEventType.PAYMENT_SUCCEEDED,
            com.skbingegalaxy.booking.entity.BookingEventType.PAYMENT_FAILED,
            com.skbingegalaxy.booking.entity.BookingEventType.CONFIRMED,
            com.skbingegalaxy.booking.entity.BookingEventType.NOTIFICATION_SENT,
            com.skbingegalaxy.booking.entity.BookingEventType.REMINDER_SENT,
            com.skbingegalaxy.booking.entity.BookingEventType.RESCHEDULED,
            com.skbingegalaxy.booking.entity.BookingEventType.TRANSFERRED,
            com.skbingegalaxy.booking.entity.BookingEventType.CHECKED_IN,
            com.skbingegalaxy.booking.entity.BookingEventType.CHECKED_OUT,
            com.skbingegalaxy.booking.entity.BookingEventType.COMPLETED,
            com.skbingegalaxy.booking.entity.BookingEventType.CANCELLED,
            com.skbingegalaxy.booking.entity.BookingEventType.NO_SHOW,
            com.skbingegalaxy.booking.entity.BookingEventType.REFUND_INITIATED,
            com.skbingegalaxy.booking.entity.BookingEventType.REFUND_COMPLETED);

    @GetMapping("/{bookingRef}/timeline")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getCustomerTimeline(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole) {
        BookingDto booking = bookingService.getByRef(bookingRef);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(userRole) || "SUPER_ADMIN".equalsIgnoreCase(userRole);
        if (!isAdmin && !userId.equals(booking.getCustomerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Not authorized to view this booking timeline"));
        }
        java.util.List<java.util.Map<String, Object>> rows = bookingService.getCustomerTimeline(
            bookingRef, CUSTOMER_VISIBLE_EVENTS);
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }
}
