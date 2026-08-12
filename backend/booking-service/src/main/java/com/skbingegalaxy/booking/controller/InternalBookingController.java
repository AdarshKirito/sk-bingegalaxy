package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.dto.ChannelCancellationRequest;
import com.skbingegalaxy.booking.dto.ChannelConfirmationRequest;
import com.skbingegalaxy.booking.dto.ChannelReservationRequest;
import com.skbingegalaxy.booking.dto.InternalBingeDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.util.OpeningHoursCodec;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    private final BookingService bookingService;

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

    /**
     * V85 (gap G2): ingest a reservation delivered by an external sales channel.
     *
     * <p><b>Why here rather than on the public API.</b> {@code POST /api/v1/bookings}
     * derives the guest from the caller's JWT — a channel guest has no SK account, so
     * there is no JWT to derive one from. Rather than inventing a new identity system,
     * this reuses the service-principal seam the platform already has: the caller
     * presents {@code X-Internal-Secret}, {@code InternalApiAuthFilter} grants
     * {@code ROLE_SYSTEM}, and {@code SecurityConfig} gates
     * {@code /api/v1/bookings/internal/**} on it.
     *
     * <p><b>Not reachable from the internet.</b> The gateway now 404s any
     * {@code /api/v*}/<service>/internal/**} path and strips a client-supplied
     * {@code X-Internal-Secret}, so this surface is callable only from inside the
     * trusted network. See {@code InternalPathBlockingTest}.
     *
     * <p><b>Provider-neutral.</b> The payload carries an opaque {@code externalSource}
     * slug. Translating a specific channel's format into it belongs to the
     * distribution context; booking-service never learns which channel is which.
     *
     * <p><b>Idempotent, and honest about it.</b> A first delivery answers <b>201
     * Created</b>; a redelivery of the same {@code (externalSource, externalRef)}
     * answers <b>200 OK</b> with the original booking. The distinction is not
     * cosmetic — a channel that receives 201 for a retry will record a second booking
     * on its side, and the two systems drift apart while both believe they are correct.
     */
    @PostMapping("/reservations")
    public ResponseEntity<ApiResponse<BookingDto>> ingestChannelReservation(
            @Valid @RequestBody ChannelReservationRequest request) {
        BookingService.ChannelIngestResult result =
            bookingService.ingestChannelReservationDetailed(request);
        return ResponseEntity
            .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.ok(
                result.created() ? "Channel reservation accepted"
                                 : "Channel reservation already recorded",
                result.booking()));
    }

    /**
     * Cancel a reservation a channel previously delivered, addressed by the channel's
     * own reference.
     *
     * <p><b>The other half of the ingestion seam.</b> Reservations could arrive but
     * never leave: a traveller who cancelled on the OTA kept a live booking here, and
     * the venue held a slot for someone who was not coming. The distribution context's
     * inbox processor calls this on a CANCEL message.
     *
     * <p>Idempotent by design — a redelivered cancel answers 200 with
     * {@code cancelled: false} rather than an error, because at-least-once delivery
     * guarantees the retry and a 4xx would trap the message in the channel's own
     * retry loop while the booking is already cancelled.
     */
    @PostMapping("/reservations/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelChannelReservation(
            @Valid @RequestBody ChannelCancellationRequest request) {
        BookingService.ChannelCancelResult result = bookingService.cancelChannelReservation(
            request.getBingeId(), request.getExternalSource(),
            request.getExternalRef(), request.getReason());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bookingRef", result.bookingRef());
        body.put("cancelled", result.cancelled());
        body.put("detail", result.detail());
        return ResponseEntity.ok(ApiResponse.ok(result.detail(), body));
    }

    /**
     * Confirm a reservation a channel previously delivered — the hold becomes a sale.
     *
     * <p><b>The third of three lifecycle messages, and the one that was missing.</b>
     * Reservations could arrive and could be cancelled; nothing could confirm one. A
     * channel reservation is created {@code PENDING}, so without this the
     * pending-timeout sweep auto-cancelled every confirmed channel sale roughly half an
     * hour after the reseller had told the traveller they were booked — silently, and
     * with every component involved reporting success.
     *
     * <p>Idempotent like its siblings: a redelivered confirmation answers 200 with
     * {@code confirmed: false} rather than erroring, because at-least-once delivery
     * guarantees the retry.
     */
    @PostMapping("/reservations/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmChannelReservation(
            @Valid @RequestBody ChannelConfirmationRequest request) {
        BookingService.ChannelConfirmResult result = bookingService.confirmChannelReservation(
            request.getBingeId(), request.getExternalSource(), request.getExternalRef());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bookingRef", result.bookingRef());
        body.put("confirmed", result.confirmed());
        body.put("detail", result.detail());
        return ResponseEntity.ok(ApiResponse.ok(result.detail(), body));
    }
}
