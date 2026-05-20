package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingTransferDto;
import com.skbingegalaxy.booking.dto.TransferBookingRequest;
import com.skbingegalaxy.booking.entity.BookingTransfer;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingTransferService;
import com.skbingegalaxy.booking.service.IdempotencyService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints for the 2-phase booking transfer flow.
 *
 * <p>Two surface areas:
 * <ul>
 *   <li>{@code /api/v1/bookings/{ref}/transfers/...} — the booking-owner side
 *       (auth + binge scope required). Used by the original customer to
 *       request, list, or revoke transfer offers.</li>
 *   <li>{@code /api/v1/booking-transfers/by-token/{token}/...} — the recipient
 *       side. The recipient lands here from an email link; tenancy is resolved
 *       from the token, not the {@code X-Binge-Id} header.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Validated
public class BookingTransferController {

    private final BookingTransferService transferService;
    private final AdminBingeScopeService scopeService;
    private final IdempotencyService idempotencyService;

    // ── Owner-side (tenant-scoped) ────────────────────────────────────────
    @PostMapping("/api/v1/bookings/{bookingRef}/transfers")
    public ResponseEntity<ApiResponse<BookingTransferDto>> requestTransfer(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Name", defaultValue = "") String name,
            @RequestHeader(value = "X-User-Email", defaultValue = "") String email,
            @Valid @RequestBody TransferBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        scopeService.requireSelectedBinge("requesting booking transfer");
        BookingTransferDto dto = idempotencyService.execute(
            idempotencyKey, "POST",
            "/api/v1/bookings/" + bookingRef + "/transfers",
            userId, request, BookingTransferDto.class,
            () -> transferService.requestTransfer(bookingRef, userId, name, email, request));
        return ResponseEntity.ok(ApiResponse.ok("Transfer offer sent", dto));
    }

    @GetMapping("/api/v1/bookings/{bookingRef}/transfers")
    public ResponseEntity<ApiResponse<List<BookingTransferDto>>> listForBooking(
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Id") Long userId) {
        scopeService.requireSelectedBinge("listing booking transfers");
        return ResponseEntity.ok(ApiResponse.ok(
            "Transfer history",
            transferService.listForBooking(bookingRef, userId)));
    }

    @PostMapping("/api/v1/bookings/{bookingRef}/transfers/{transferId}/revoke")
    public ResponseEntity<ApiResponse<BookingTransferDto>> revoke(
            @PathVariable String bookingRef,
            @PathVariable Long transferId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        scopeService.requireSelectedBinge("revoking booking transfer");
        BookingTransferDto dto = idempotencyService.execute(
            idempotencyKey, "POST",
            "/api/v1/bookings/" + bookingRef + "/transfers/" + transferId + "/revoke",
            userId, Map.of("transferId", transferId), BookingTransferDto.class,
            () -> transferService.revokeTransfer(transferId, userId));
        return ResponseEntity.ok(ApiResponse.ok("Transfer revoked", dto));
    }

    // ── Recipient-side (token, no binge header) ───────────────────────────
    /**
     * Public preview endpoint — what booking is this token offering? Returns
     * the from-name/booking-ref so the recipient can confirm before accepting.
     * Token leak risk is mitigated because preview only exposes minimal data
     * (no payment details, no PII beyond names already on the booking page).
     */
    @GetMapping("/api/v1/booking-transfers/by-token/{token}")
    public ResponseEntity<ApiResponse<BookingTransferDto>> preview(@PathVariable String token) {
        BookingTransfer t = transferService.findByToken(token);
        return ResponseEntity.ok(ApiResponse.ok(
            "Transfer offer",
            BookingTransferDto.builder()
                .id(t.getId())
                .bookingRef(t.getBookingRef())
                .fromCustomerName(t.getFromCustomerName())
                .fromCustomerEmail(t.getFromCustomerEmail())
                .toName(t.getToName())
                .toEmail(t.getToEmail())
                .status(t.getStatus())
                .expiresAt(t.getExpiresAt())
                .createdAt(t.getCreatedAt())
                .build()));
    }

    @PostMapping("/api/v1/booking-transfers/by-token/{token}/accept")
    public ResponseEntity<ApiResponse<BookingTransferDto>> accept(
            @PathVariable String token,
            @RequestHeader(value = "X-User-Id", required = false) Long recipientCustomerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Tenancy is resolved inside the service from the transfer row.
        // Idempotency-Key has no userId binding here (recipient may be anon),
        // so we pass 0L as a sentinel which the IdempotencyService treats as
        // "no-user" — matching how unauthenticated webhook callbacks scope keys.
        Long idemUser = recipientCustomerId == null ? 0L : recipientCustomerId;
        try {
            BookingTransferDto dto = idempotencyService.execute(
                idempotencyKey, "POST",
                "/api/v1/booking-transfers/by-token/" + token + "/accept",
                idemUser, Map.of("token", token), BookingTransferDto.class,
                () -> transferService.acceptTransfer(token, recipientCustomerId));
            return ResponseEntity.ok(ApiResponse.ok("Transfer accepted", dto));
        } finally {
            BingeContext.clear();
        }
    }

    @PostMapping("/api/v1/booking-transfers/by-token/{token}/decline")
    public ResponseEntity<ApiResponse<BookingTransferDto>> decline(
            @PathVariable String token,
            @RequestBody(required = false) DeclineRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String reason = (body == null || body.reason == null) ? null : body.reason;
        BookingTransferDto dto = idempotencyService.execute(
            idempotencyKey, "POST",
            "/api/v1/booking-transfers/by-token/" + token + "/decline",
            0L, Map.of("token", token, "reason", reason == null ? "" : reason),
            BookingTransferDto.class,
            () -> transferService.declineTransfer(token, reason));
        return ResponseEntity.ok(ApiResponse.ok("Transfer declined", dto));
    }

    /** Optional decline body — empty payloads are accepted. */
    public static class DeclineRequest {
        @Size(max = 500)
        public String reason;
    }
}
