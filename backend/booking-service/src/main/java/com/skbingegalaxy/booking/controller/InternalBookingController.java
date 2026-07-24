package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.InternalBingeDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.util.OpeningHoursCodec;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Internal-only endpoints for service-to-service calls.
 * Protected by {@link com.skbingegalaxy.common.security.InternalApiAuthFilter}.
 */
@RestController
@RequestMapping("/api/v1/bookings/internal")
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingRepository bookingRepository;
    private final BingeRepository bingeRepository;
    private final com.skbingegalaxy.booking.permission.BingeModulePermissionService modulePermissionService;

    /**
     * Binge ownership + operating snapshot for sibling services.
     *
     * <p>Payment- and availability-service enforce "does this admin manage the
     * selected binge?" before any {@code /admin/**} operation. They MUST read
     * {@code adminId} from here — the public {@code /binges/{id}} endpoint
     * strips it, and it also hides non-approved venues that admins still need
     * to manage.</p>
     */
    @GetMapping("/binges/{id}")
    public ResponseEntity<ApiResponse<InternalBingeDto>> getBinge(
            @PathVariable Long id,
            @RequestParam(required = false) Long userId) {
        Binge b = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        return ResponseEntity.ok(ApiResponse.ok(InternalBingeDto.builder()
            .id(b.getId())
            .adminId(b.getAdminId())
            .active(b.isActive())
            .status(b.getStatus() != null ? b.getStatus().name() : null)
            .timezone(b.getTimezone())
            .currency(b.getCurrency())
            .country(b.getCountry())
            .openTime(b.getOpenTime())
            .closeTime(b.getCloseTime())
            .openingHours(OpeningHoursCodec.parse(b.getOpeningHoursJson()))
            // V71 module matrix: lets the calling service enforce its own
            // modules (DISPUTES / FAILED_REFUNDS / BLOCKED_DATES) in the same
            // round trip it already makes for the ownership check.
            .deniedModules(userId == null ? null
                : java.util.List.copyOf(modulePermissionService.deniedModules(id, userId)))
            .build()));
    }

    /**
     * Authoritative booking snapshot for payment-service (SEC-011).
     *
     * <p>Beyond the amounts, this exposes the booking's OWNER and BINGE so
     * every payment write can be bound to the authoritative aggregate instead
     * of trusting the caller's request body or the client-controlled
     * {@code X-Binge-Id} header. Payment-service rejects any initiation or
     * manual payment whose actor/scope doesn't match these fields.</p>
     */
    @GetMapping("/amount/{bookingRef}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBookingAmount(
            @PathVariable String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));

        BigDecimal collected = booking.getCollectedAmount() != null
            ? booking.getCollectedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = booking.getTotalAmount().subtract(collected);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;

        // Expose the locked payment currency + rate so payment-service can validate a
        // foreign-currency charge against the SAME rate the booking was created at.
        // Defaults: base currency (INR) at rate 1 for ordinary domestic bookings.
        String paymentCurrencyCode = booking.getPaymentCurrencyCode() != null
            ? booking.getPaymentCurrencyCode() : "INR";
        BigDecimal fxRate = booking.getFxRate() != null ? booking.getFxRate() : BigDecimal.ONE;

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bookingId", booking.getId());
        body.put("totalAmount", booking.getTotalAmount());
        body.put("collectedAmount", collected);
        body.put("remainingBalance", remaining);
        body.put("status", booking.getStatus().name());
        body.put("paymentCurrencyCode", paymentCurrencyCode);
        body.put("fxRate", fxRate);
        // Authoritative ownership/tenancy — Map.of can't hold these because
        // customerId/bingeId may be null on legacy rows.
        body.put("customerId", booking.getCustomerId());
        body.put("bingeId", booking.getBingeId());
        // The VENUE's country decides which payment methods are offered — a US
        // customer paying an Indian venue must see UPI, not PayPal. Payment-service
        // resolves its method catalogue from this, so it travels in the same round
        // trip rather than costing a second internal call. Null on legacy venues:
        // payment-service falls back to a card-only international default.
        body.put("bingeCountry", booking.getBingeId() == null ? null
            : bingeRepository.findById(booking.getBingeId())
                .map(Binge::getCountry)
                .orElse(null));
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
