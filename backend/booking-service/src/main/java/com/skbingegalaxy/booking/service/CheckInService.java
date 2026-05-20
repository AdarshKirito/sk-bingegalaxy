package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BookingDto;
import com.skbingegalaxy.booking.dto.UpdateBookingRequest;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.entity.CheckInToken;
import com.skbingegalaxy.booking.entity.CheckInToken.TokenType;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CheckInTokenRepository;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.booking.web.RequestContext;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Issues and verifies QR / OTP check-in tokens with strict window-of-validity
 * rules. Designed for front-desk operations:
 *
 * <ul>
 *   <li><b>QR</b> — admin (or customer email link) issues a one-time URL-safe
 *       token. The customer presents the QR code; admin scanner POSTs the
 *       token to verify + check the booking in.</li>
 *   <li><b>OTP</b> — admin issues a 6-digit code that is sent to the customer
 *       (via existing notification pipeline). Admin types the code into the
 *       front-desk UI to verify identity at check-in.</li>
 * </ul>
 *
 * <p>Window rule: a token can only be <i>verified</i> when {@code now} is in
 * {@code [startTime - earlyMinutes, startTime + lateMinutes]} on the booking
 * date. Tokens may be issued earlier (so confirmation emails can ship the QR),
 * but cannot be redeemed until the window opens — preventing abuse.
 *
 * <p>OTP codes are stored only as SHA-256 hashes; the live code is sent to
 * the customer once via {@link NotificationProducer} and never persisted in
 * plain form.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInService {

    private static final SecureRandom RNG = new SecureRandom();

    private final CheckInTokenRepository tokenRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final BookingEventLogService eventLogService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.checkin.window-early-minutes:30}")
    private int windowEarlyMinutes;

    @Value("${app.checkin.window-late-minutes:60}")
    private int windowLateMinutes;

    @Value("${app.checkin.otp-ttl-minutes:15}")
    private int otpTtlMinutes;

    @Value("${app.checkin.qr-ttl-hours:48}")
    private int qrTtlHours;

    @Value("${app.checkin.otp-max-attempts:5}")
    private int otpMaxAttempts;

    // ── Issue ────────────────────────────────────────────────────────────────

    /**
     * Issues a fresh QR token for the given booking. Any previously-active
     * (un-consumed, un-expired) QR tokens for this booking are invalidated to
     * keep "one live token per booking" semantics.
     */
    @Transactional
    public IssueQrResult issueQrToken(String bookingRef, String issuedBy) {
        Booking booking = loadBookingForIssue(bookingRef);

        // Invalidate prior active QR tokens
        List<CheckInToken> prior = tokenRepository
            .findActiveByBookingRefAndType(bookingRef, TokenType.QR);
        LocalDateTime now = LocalDateTime.now();
        for (CheckInToken t : prior) {
            t.setExpiresAt(now);
        }

        String token = generateUrlSafeToken();
        CheckInToken saved = tokenRepository.save(CheckInToken.builder()
            .bookingRef(booking.getBookingRef())
            .bookingId(booking.getId())
            .tokenType(TokenType.QR)
            .tokenValue(token)
            .issuedBy(issuedBy)
            .expiresAt(now.plusHours(qrTtlHours))
            .bingeId(booking.getBingeId())
            .build());

        return new IssueQrResult(token, saved.getExpiresAt());
    }

    /**
     * Issues a fresh 6-digit OTP for the given booking. Hashes the code,
     * persists the hash, and emits a {@code CHECK_IN_OTP} notification so the
     * notification-service (or downstream channel) can deliver it.
     *
     * @return the live 6-digit code so the API caller (admin UI) can also
     *         display it to the customer if needed.
     */
    @Transactional
    public IssueOtpResult issueOtp(String bookingRef, String issuedBy) {
        Booking booking = loadBookingForIssue(bookingRef);

        // Invalidate any prior active OTPs
        List<CheckInToken> prior = tokenRepository
            .findActiveByBookingRefAndType(bookingRef, TokenType.OTP);
        LocalDateTime now = LocalDateTime.now();
        for (CheckInToken t : prior) {
            t.setExpiresAt(now);
        }

        String code = generateNumericOtp(6);
        String hash = sha256(code);

        CheckInToken saved = tokenRepository.save(CheckInToken.builder()
            .bookingRef(booking.getBookingRef())
            .bookingId(booking.getId())
            .tokenType(TokenType.OTP)
            .tokenValue(hash)
            .issuedBy(issuedBy)
            .expiresAt(now.plusMinutes(otpTtlMinutes))
            .bingeId(booking.getBingeId())
            .build());

        // Best-effort delivery; failure should not break OTP issuance — admin can re-issue
        try {
            enqueueOtpNotification(booking, code, otpTtlMinutes);
        } catch (Exception e) {
            log.warn("Failed to enqueue OTP notification for {}: {}", bookingRef, e.getMessage());
        }

        return new IssueOtpResult(code, saved.getExpiresAt());
    }

    // ── Verify ───────────────────────────────────────────────────────────────

    /**
     * Verifies a QR token and checks the booking in. Idempotent against
     * already-consumed tokens (returns the consumed token's booking state if
     * already CHECKED_IN; otherwise fails).
     */
    @Transactional
    public BookingDto verifyQr(String token, Long actorAdminId, String actorEmail, LocalDate clientDate) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("Missing QR token");
        }
        CheckInToken found = tokenRepository
            .findByValueAndTypeForUpdate(token.trim(), TokenType.QR)
            .orElseThrow(() -> new BusinessException("QR token is invalid or has expired"));
        return consumeAndCheckIn(found, actorAdminId, actorEmail, "QR", clientDate);
    }

    /**
     * Verifies a 6-digit OTP for the given booking and checks it in.
     * Wrong codes increment {@code failedAttempts} on the active OTP record;
     * exceeding {@code otpMaxAttempts} immediately invalidates the OTP.
     */
    @Transactional
    public BookingDto verifyOtp(String bookingRef, String code, Long actorAdminId, String actorEmail, LocalDate clientDate) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BusinessException("Missing booking reference");
        }
        if (code == null || !code.matches("\\d{4,8}")) {
            throw new BusinessException("OTP must be a 4–8 digit numeric code");
        }
        List<CheckInToken> active = tokenRepository
            .findActiveByBookingRefForUpdate(bookingRef.trim(), TokenType.OTP);
        if (active.isEmpty()) {
            throw new BusinessException("No active OTP for this booking — please request a new code");
        }
        // Use most recent (already DESC sorted)
        CheckInToken token = active.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (token.isExpired(now)) {
            throw new BusinessException("OTP has expired — please request a new code");
        }
        String submittedHash = sha256(code.trim());
        if (!constantTimeEquals(submittedHash, token.getTokenValue())) {
            token.setFailedAttempts(token.getFailedAttempts() + 1);
            if (token.getFailedAttempts() >= otpMaxAttempts) {
                token.setExpiresAt(now);
                throw new BusinessException("Too many incorrect attempts — OTP locked. Please request a new code");
            }
            int remaining = otpMaxAttempts - token.getFailedAttempts();
            throw new BusinessException("Incorrect OTP. " + remaining + " attempt(s) remaining");
        }
        return consumeAndCheckIn(token, actorAdminId, actorEmail, "OTP", clientDate);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Booking loadBookingForIssue(String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));
        if (booking.getStatus() == BookingStatus.CANCELLED
                || booking.getStatus() == BookingStatus.NO_SHOW
                || booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                "Cannot issue check-in token — booking status is " + booking.getStatus());
        }
        if (booking.getStatus() == BookingStatus.CHECKED_IN) {
            throw new BusinessException("Booking is already checked in");
        }
        return booking;
    }

    private BookingDto consumeAndCheckIn(CheckInToken token, Long actorAdminId, String actorEmail,
                                         String source, LocalDate clientDate) {
        LocalDateTime now = LocalDateTime.now();
        if (token.isConsumed()) {
            throw new BusinessException("This " + token.getTokenType()
                + " token has already been used");
        }
        if (token.isExpired(now)) {
            throw new BusinessException(token.getTokenType() + " token has expired");
        }
        Booking booking = bookingRepository.findById(token.getBookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", token.getBookingId()));

        // Status guard — only CONFIRMED can be checked in via this path
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("Cannot check in — booking status is "
                + booking.getStatus() + " (must be CONFIRMED)");
        }

        // Operational-day guard (matches existing manual-checkin behaviour)
        LocalDate opDate = systemSettingsService.getOperationalDate(booking.getBingeId(), clientDate);
        if (!booking.getBookingDate().equals(opDate)) {
            throw new BusinessException(
                "This booking is for " + booking.getBookingDate()
                + " — outside the current operational day (" + opDate + ")");
        }

        // Window-of-validity guard
        LocalDateTime windowStart = LocalDateTime.of(booking.getBookingDate(),
            booking.getStartTime()).minusMinutes(windowEarlyMinutes);
        LocalDateTime windowEnd = LocalDateTime.of(booking.getBookingDate(),
            booking.getStartTime()).plusMinutes(windowLateMinutes);
        if (now.isBefore(windowStart)) {
            throw new BusinessException(
                "Check-in opens at " + windowStart.toLocalTime() + " (" + windowEarlyMinutes
                + " min before start)");
        }
        if (now.isAfter(windowEnd)) {
            throw new BusinessException(
                "Check-in closed at " + windowEnd.toLocalTime() + " (" + windowLateMinutes
                + " min after start). Mark as no-show or contact support.");
        }

        // Consume token first (race-safe under the pessimistic lock acquired by
        // findByValueAndTypeForUpdate / findActiveByBookingRefForUpdate)
        token.setConsumedAt(now);
        token.setConsumedBy(actorEmail);
        token.setConsumedIp(RequestContext.currentIp());

        // Reuse the canonical check-in path so all existing audit + projection
        // logic fires identically to a manual admin check-in.
        UpdateBookingRequest req = new UpdateBookingRequest();
        req.setCheckedIn(true);
        BookingDto dto = bookingService.updateBooking(booking.getBookingRef(), req);

        // Append a source-of-truth audit row tagged with the channel used.
        Booking refreshed = bookingRepository.findById(booking.getId()).orElse(booking);

        // Late-arrival flag — derived from the strict scheduled start time
        // (NOT the early-window start). Matches what a guest would consider
        // "late". Persisted so the admin UI can render a "LATE_ARRIVAL" pill
        // and reports can filter on it without having to recompute from logs.
        LocalDateTime scheduledStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        if (now.isAfter(scheduledStart) && !refreshed.isLateArrival()) {
            refreshed.setLateArrival(true);
            bookingRepository.save(refreshed);
        }

        eventLogService.logEventFull(
            refreshed, BookingEventType.CHECKED_IN, "CONFIRMED",
            actorAdminId, "ADMIN", actorEmail,
            "Checked in via " + source + (refreshed.isLateArrival() ? " (LATE)" : ""),
            null,
            RequestContext.currentIp(),
            RequestContext.currentUserAgent());

        return dto;
    }

    // ── Crypto helpers ───────────────────────────────────────────────────────

    private void enqueueOtpNotification(Booking booking, String code, int ttlMinutes) throws Exception {
        String subject = "Your check-in code for booking " + booking.getBookingRef();
        String body = String.format(
            "Hi %s,%n%nYour check-in code is: %s%n%nThis code is valid for %d minutes." +
            "%nShare it with the front-desk only — never with anyone else.%n%nBooking ref: %s",
            booking.getCustomerName(), code, ttlMinutes, booking.getBookingRef());
        NotificationEvent ev = NotificationEvent.builder()
            .type("CHECK_IN_OTP")
            .channel(NotificationChannel.EMAIL)
            .recipientEmail(booking.getCustomerEmail())
            .recipientName(booking.getCustomerName())
            .recipientPhone(booking.getCustomerPhone())
            .recipientPhoneCountryCode(booking.getCustomerPhoneCountryCode())
            .subject(subject)
            .body(body)
            .bookingRef(booking.getBookingRef())
            .metadata(Map.of(
                "bookingRef", booking.getBookingRef(),
                "ttlMinutes", String.valueOf(ttlMinutes),
                "codeLength", String.valueOf(code.length())))
            .build();
        String payload = objectMapper.writeValueAsString(ev);
        OutboxEvent outbox = OutboxEvent.builder()
            .topic(KafkaTopics.NOTIFICATION_SEND)
            .aggregateKey("CHKIN-OTP-" + booking.getBookingRef())
            .payload(payload)
            .build();
        outboxEventRepository.save(outbox);
    }
    private static String generateUrlSafeToken() {
        byte[] bytes = new byte[24]; // 192 bits → 32 chars base64url
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateNumericOtp(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ── Result records ───────────────────────────────────────────────────────

    public record IssueQrResult(String token, LocalDateTime expiresAt) {}
    public record IssueOtpResult(String otp, LocalDateTime expiresAt) {}
}
