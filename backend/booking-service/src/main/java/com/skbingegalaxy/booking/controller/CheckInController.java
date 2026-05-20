package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.CheckInService;
import com.skbingegalaxy.booking.service.IdempotencyService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Front-desk check-in endpoints. All endpoints are admin-gated by the
 * gateway's {@code JwtAuthenticationFilter} (5th path segment is
 * {@code /admin/}).
 *
 * <ul>
 *   <li>{@code POST /admin/{ref}/check-in/qr/issue} — generate a fresh QR
 *       token (URL-safe, 48h TTL).</li>
 *   <li>{@code POST /admin/{ref}/check-in/otp/issue} — generate a fresh
 *       6-digit OTP (15min TTL) and dispatch it via the notification
 *       pipeline.</li>
 *   <li>{@code POST /admin/check-in/verify} — verify either a QR token or
 *       a (bookingRef, OTP) tuple and check the booking in.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin")
@RequiredArgsConstructor
@Validated
@Slf4j
public class CheckInController {

    private final AdminBingeScopeService adminBingeScopeService;
    private final CheckInService checkInService;
    private final IdempotencyService idempotencyService;

    @org.springframework.beans.factory.annotation.Value("${app.checkin.return-otp-in-response:true}")
    private boolean returnOtpInResponse;

    @ModelAttribute
    void validateSelectedBinge() {
        adminBingeScopeService.requireSelectedBinge("issuing or verifying check-in tokens");
    }

    // ── Issue ────────────────────────────────────────────────────────────────

    @PostMapping("/{bookingRef}/check-in/qr/issue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueQr(
            @PathVariable String bookingRef,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "X-User-Email", required = false) String adminEmail,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Map<String, Object> result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/check-in/qr/issue",
            adminId, Map.of("bookingRef", bookingRef), Map.class,
            () -> {
                CheckInService.IssueQrResult res = checkInService.issueQrToken(bookingRef, adminEmail);
                return Map.of(
                    "bookingRef", bookingRef,
                    "token", res.token(),
                    "expiresAt", res.expiresAt().toString());
            });
        return ResponseEntity.ok(ApiResponse.ok("QR token issued", result));
    }

    @PostMapping("/{bookingRef}/check-in/otp/issue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueOtp(
            @PathVariable String bookingRef,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "X-User-Email", required = false) String adminEmail,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Map<String, Object> result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/" + bookingRef + "/check-in/otp/issue",
            adminId, Map.of("bookingRef", bookingRef), Map.class,
            () -> {
                CheckInService.IssueOtpResult res = checkInService.issueOtp(bookingRef, adminEmail);
                // The OTP itself is only included when `app.checkin.return-otp-in-response`
                // is true (ops fallback so the front-desk admin can read it aloud if the
                // customer has trouble receiving the SMS/email). In hardened deployments
                // this can be flipped off so the live code never leaves the customer's inbox.
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("bookingRef", bookingRef);
                body.put("expiresAt", res.expiresAt().toString());
                if (returnOtpInResponse) {
                    body.put("otp", res.otp());
                }
                return body;
            });
        return ResponseEntity.ok(ApiResponse.ok("OTP issued", result));
    }

    // ── Verify ───────────────────────────────────────────────────────────────

    public record VerifyRequest(
            String token,
            String bookingRef,
            @Pattern(regexp = "^\\d{4,8}$", message = "OTP must be 4–8 digits") String otp,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {}

    @PostMapping("/check-in/verify")
    public ResponseEntity<ApiResponse<BookingDto>> verify(
            @RequestBody VerifyRequest body,
            @RequestHeader(value = "X-User-Id", required = false) Long adminId,
            @RequestHeader(value = "X-User-Email", required = false) String adminEmail,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Idempotency on (token | bookingRef+otp)
        String idempotencyResource = body.token() != null
            ? "qr:" + body.token()
            : "otp:" + (body.bookingRef() == null ? "" : body.bookingRef());
        BookingDto result = idempotencyService.execute(
            idempotencyKey, "POST", "/api/v1/bookings/admin/check-in/verify:" + idempotencyResource,
            adminId,
            Map.of("token", body.token() == null ? "" : "***",
                   "bookingRef", body.bookingRef() == null ? "" : body.bookingRef()),
            BookingDto.class,
            () -> {
                if (body.token() != null && !body.token().isBlank()) {
                    return checkInService.verifyQr(body.token(), adminId, adminEmail, body.clientDate());
                }
                if (body.bookingRef() != null && body.otp() != null) {
                    return checkInService.verifyOtp(body.bookingRef(), body.otp(),
                        adminId, adminEmail, body.clientDate());
                }
                throw new com.skbingegalaxy.common.exception.BusinessException(
                    "Provide either a QR `token`, or a `bookingRef` + `otp`");
            });
        return ResponseEntity.ok(ApiResponse.ok("Check-in completed", result));
    }
}
