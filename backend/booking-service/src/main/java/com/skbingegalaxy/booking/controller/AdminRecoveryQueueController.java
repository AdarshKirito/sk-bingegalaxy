package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.SlotHold;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.SlotHoldRepository;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.SlotHoldService;
import com.skbingegalaxy.booking.service.VenueClockService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-grade recovery-first ops queues. Each endpoint surfaces a
 * specific failure mode that on-call admins must reconcile:
 *
 * <ul>
 *   <li>Stuck pending — payment never started, booking abandoned mid-flow.
 *   Cancel + release slot, or contact customer.</li>
 *   <li>Expired holds not released — slot-hold expiry scheduler is dead;
 *   inventory is leaking. Investigate scheduler health.</li>
 *   <li>Paid but not confirmed — payment.success → BOOKING_CONFIRMED saga
 *   is stuck. Replay the BOOKING_CONFIRMED side, or re-fire the payment
 *   webhook from the gateway dashboard.</li>
 *   <li>No-show — completed window passed, guest never checked in. Triggers
 *   freeze policy; admin verifies before deciding partial refund.</li>
 * </ul>
 *
 * <p>Backed by the same security as {@link AdminOpsController} — ROLE_ADMIN /
 * ROLE_SUPER_ADMIN. Each endpoint is read-only; recovery actions live on the
 * regular admin endpoints (cancel, release-hold, replay-event).
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/recovery")
@RequiredArgsConstructor
@Slf4j
public class AdminRecoveryQueueController {

    private final BookingRepository bookingRepository;
    private final SlotHoldRepository slotHoldRepository;
    private final BookingService bookingService;
    private final SlotHoldService slotHoldService;
    private final AdminBingeScopeService adminBingeScopeService;
    private final VenueClockService venueClock;

    /** Bookings that have been PENDING + payment-PENDING for too long — abandoned mid-checkout. */
    @GetMapping("/stuck-pending")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stuckPending(
            @RequestParam(defaultValue = "60") int olderThanMinutes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (olderThanMinutes < 1) olderThanMinutes = 60;
        size = capSize(size);
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(olderThanMinutes);
        Page<Booking> p = bookingRepository.findStuckPending(cutoff, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toQueueResponse("stuck-pending", p, b -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingRef", b.getBookingRef());
            row.put("customerId", b.getCustomerId());
            row.put("customerEmail", b.getCustomerEmail());
            row.put("amount", b.getTotalAmount());
            row.put("createdAt", b.getCreatedAt());
            row.put("ageMinutes", java.time.temporal.ChronoUnit.MINUTES.between(b.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)));
            return row;
        })));
    }

    /** Slot holds whose TTL elapsed but the expiry scheduler hasn't released. Indicates scheduler health issue. */
    @GetMapping("/expired-holds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> expiredHolds(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = capSize(size);
        Page<SlotHold> p = slotHoldRepository.findExpiredNotReleased(LocalDateTime.now(ZoneOffset.UTC),
            PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toQueueResponse("expired-holds", p, h -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.getId());
            row.put("holdToken", h.getHoldToken());
            row.put("customerId", h.getCustomerId());
            row.put("bingeId", h.getBingeId());
            row.put("bookingDate", h.getBookingDate());
            row.put("expiresAt", h.getExpiresAt());
            row.put("overdueMinutes", java.time.temporal.ChronoUnit.MINUTES.between(h.getExpiresAt(), LocalDateTime.now(ZoneOffset.UTC)));
            return row;
        })));
    }

    /** Customer paid but BOOKING_CONFIRMED never landed — saga side is stuck. */
    @GetMapping("/paid-not-confirmed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> paidNotConfirmed(
            @RequestParam(defaultValue = "5") int olderThanMinutes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (olderThanMinutes < 1) olderThanMinutes = 5;
        size = capSize(size);
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(olderThanMinutes);
        Page<Booking> p = bookingRepository.findPaidButNotConfirmed(cutoff, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toQueueResponse("paid-not-confirmed", p, b -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingRef", b.getBookingRef());
            row.put("customerId", b.getCustomerId());
            row.put("customerEmail", b.getCustomerEmail());
            row.put("amount", b.getTotalAmount());
            row.put("collectedAmount", b.getCollectedAmount());
            row.put("paidAt", b.getUpdatedAt());
            row.put("staleMinutes", java.time.temporal.ChronoUnit.MINUTES.between(b.getUpdatedAt(), LocalDateTime.now(ZoneOffset.UTC)));
            return row;
        })));
    }

    /** Bookings that ended NO_SHOW — admins follow up + reconcile partial refunds. */
    @GetMapping("/no-show")
    public ResponseEntity<ApiResponse<Map<String, Object>>> noShow(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = capSize(size);
        LocalDate today = venueClock.today(BingeContext.getBingeId());
        LocalDate f = from != null ? from : today.minusDays(7);
        LocalDate t = to != null ? to : today;
        Page<Booking> p = bookingRepository.findNoShowBookings(f, t, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toQueueResponse("no-show", p, b -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingRef", b.getBookingRef());
            row.put("customerId", b.getCustomerId());
            row.put("customerEmail", b.getCustomerEmail());
            row.put("bookingDate", b.getBookingDate());
            row.put("startTime", b.getStartTime());
            row.put("amount", b.getTotalAmount());
            row.put("collectedAmount", b.getCollectedAmount());
            return row;
        })));
    }

    /** Aggregated counts for an ops dashboard widget. */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary() {
        long stuckPending = bookingRepository.findStuckPending(
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(60), PageRequest.of(0, 1)).getTotalElements();
        long expiredHolds = slotHoldRepository.findExpiredNotReleased(
            LocalDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 1)).getTotalElements();
        long paidNotConfirmed = bookingRepository.findPaidButNotConfirmed(
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), PageRequest.of(0, 1)).getTotalElements();
        LocalDate summaryToday = venueClock.today(BingeContext.getBingeId());
        long noShow = bookingRepository.findNoShowBookings(
            summaryToday.minusDays(7), summaryToday, PageRequest.of(0, 1)).getTotalElements();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stuckPending", stuckPending);
        body.put("expiredHolds", expiredHolds);
        body.put("paidNotConfirmed", paidNotConfirmed);
        body.put("noShowLast7d", noShow);
        body.put("status", (stuckPending + expiredHolds + paidNotConfirmed) == 0 ? "OK" : "DEGRADED");
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── recovery actions ─────────────────────────────────────
    // Each action is idempotent and audited via BookingEventLog.

    /**
     * Force-release a leaked SlotHold whose TTL has elapsed but the expiry
     * scheduler missed. Idempotent — already-RELEASED holds return their
     * current state without erroring.
     */
    @PostMapping("/expired-holds/{token}/release")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> releaseStaleHold(
            @PathVariable String token,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "ADMIN_RECOVERY_RELEASE")
                                     : "ADMIN_RECOVERY_RELEASE";
        // adminAccess=true bypasses customerId ownership check; null customerId is fine.
        var dto = slotHoldService.releaseByToken(token, null, true, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", dto.getHoldToken());
        out.put("status", dto.getStatus());
        out.put("reason", reason);
        log.warn("Recovery: stale slot hold {} released by admin (reason='{}')", token, reason);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Force-cancel a booking that has been stuck in PENDING beyond its checkout
     * window. Uses the same system-cancel path as the timeout scheduler so
     * compensation, refunds, and slot release all run.
     */
    @PostMapping("/stuck-pending/{bookingRef}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelStuckPending(
            @PathVariable String bookingRef,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null && body.get("reason") != null
            ? body.get("reason")
            : "Admin recovery: stuck-pending cancellation";
        var dto = bookingService.cancelBookingForSystem(bookingRef, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingRef", dto.getBookingRef());
        out.put("status", dto.getStatus());
        out.put("reason", reason);
        log.warn("Recovery: stuck-pending booking {} cancelled by admin (reason='{}')", bookingRef, reason);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Replay the booking-confirmation side-effect for a paid-but-not-confirmed
     * booking. Re-applies the post-payment status decision the saga should
     * have made. Idempotent — already-CONFIRMED bookings are returned as-is.
     */
    @PostMapping("/paid-not-confirmed/{bookingRef}/replay")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replayConfirmation(
            @PathVariable String bookingRef) {
        Booking b = bookingService.getBookingEntityForSystem(bookingRef);
        if (b.getPaymentStatus() != PaymentStatus.SUCCESS
            && b.getPaymentStatus() != PaymentStatus.PARTIALLY_PAID) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                "Booking " + bookingRef + " has paymentStatus=" + b.getPaymentStatus()
                + " — replay is only valid for SUCCESS/PARTIALLY_PAID."));
        }
        // Re-trigger the same status update used by the payment listener; this
        // re-runs the "if collected >= total → CONFIRMED, else PARTIALLY_PAID"
        // decision that the original saga step should have made.
        bookingService.updatePaymentStatus(bookingRef, b.getPaymentStatus(), null);
        Booking after = bookingService.getBookingEntityForSystem(bookingRef);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingRef", after.getBookingRef());
        out.put("status", after.getStatus().name());
        out.put("paymentStatus", after.getPaymentStatus().name());
        log.warn("Recovery: paid-not-confirmed replay for {} → status={}", bookingRef, after.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Conversion / abandonment funnel for the currently selected binge over a
     * date range (defaults to last 7 days). Counts bookings by createdAt so
     * abandoned/cancelled rows still appear at the "Started" stage.
     *
     * Stages:
     *   started   = all bookings created in range
     *   paid      = SUCCESS + PARTIALLY_PAID payments
     *   confirmed = CONFIRMED + CHECKED_IN + COMPLETED
     *   completed = COMPLETED
     * Drop-offs:
     *   abandonedAuto       = CANCELLED by SYSTEM (timeout / hold expiry)
     *   cancelledByCustomer = CANCELLED by CUSTOMER
     *   noShow              = NO_SHOW
     */
    @GetMapping("/funnel")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> funnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        adminBingeScopeService.requireSelectedBinge("viewing checkout funnel");
        Long bid = BingeContext.getBingeId();
        LocalDate today = venueClock.today(bid);
        LocalDate f = from != null ? from : today.minusDays(7);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("'to' must be on or after 'from'"));
        }
        java.time.LocalDateTime fromDt = f.atStartOfDay();
        java.time.LocalDateTime toDt = t.plusDays(1).atStartOfDay();

        long started = bookingRepository.countCreatedInRange(bid, fromDt, toDt);
        long paid = bookingRepository.countCreatedInRangeByPaymentStatus(bid, fromDt, toDt, PaymentStatus.SUCCESS)
                  + bookingRepository.countCreatedInRangeByPaymentStatus(bid, fromDt, toDt, PaymentStatus.PARTIALLY_PAID);
        long confirmed = bookingRepository.countCreatedInRangeByStatus(bid, fromDt, toDt, BookingStatus.CONFIRMED)
                       + bookingRepository.countCreatedInRangeByStatus(bid, fromDt, toDt, BookingStatus.CHECKED_IN)
                       + bookingRepository.countCreatedInRangeByStatus(bid, fromDt, toDt, BookingStatus.COMPLETED);
        long completed = bookingRepository.countCreatedInRangeByStatus(bid, fromDt, toDt, BookingStatus.COMPLETED);
        long abandonedAuto = bookingRepository.countCancelledByActor(bid, fromDt, toDt, "SYSTEM");
        long cancelledByCustomer = bookingRepository.countCancelledByActor(bid, fromDt, toDt, "CUSTOMER");
        long noShow = bookingRepository.countCreatedInRangeByStatus(bid, fromDt, toDt, BookingStatus.NO_SHOW);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", f);
        body.put("to", t);
        body.put("started", started);
        body.put("paid", paid);
        body.put("confirmed", confirmed);
        body.put("completed", completed);
        body.put("abandonedAuto", abandonedAuto);
        body.put("cancelledByCustomer", cancelledByCustomer);
        body.put("noShow", noShow);
        body.put("conversionRate", started > 0 ? (double) confirmed / started : 0.0);
        body.put("abandonmentRate", started > 0 ? (double) abandonedAuto / started : 0.0);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── helpers ──────────────────────────────────────────────

    private static int capSize(int size) {
        if (size < 1) return 50;
        return Math.min(size, 200);
    }

    private static <T> Map<String, Object> toQueueResponse(String queue, Page<T> page,
                                                           java.util.function.Function<T, Map<String, Object>> mapper) {
        List<Map<String, Object>> rows = page.getContent().stream().map(mapper).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queue", queue);
        body.put("page", page.getNumber());
        body.put("size", page.getSize());
        body.put("total", page.getTotalElements());
        body.put("rows", rows);
        return body;
    }
}
