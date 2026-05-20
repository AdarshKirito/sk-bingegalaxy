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
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * Unit tests for the verified check-in flow (QR + OTP).
 *
 * <p>The tests use a real {@code CheckInService} with mocked collaborators. Because
 * {@code consumeAndCheckIn} delegates to {@code BookingService.updateBooking}, we
 * stub that to return a CHECKED_IN DTO and verify both the audit log call and the
 * canonical update path.
 */
@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

    private static final String REF = "SKBG26ABC12345";

    @Mock private CheckInTokenRepository tokenRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService bookingService;
    @Mock private BookingEventLogService eventLogService;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private SystemSettingsService systemSettingsService;

    @InjectMocks private CheckInService checkInService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(checkInService, "windowEarlyMinutes", 30);
        ReflectionTestUtils.setField(checkInService, "windowLateMinutes", 60);
        ReflectionTestUtils.setField(checkInService, "otpTtlMinutes", 15);
        ReflectionTestUtils.setField(checkInService, "qrTtlHours", 48);
        ReflectionTestUtils.setField(checkInService, "otpMaxAttempts", 5);
    }

    // ── Issue paths ────────────────────────────────────────────────────────

    @Test
    void issueQrToken_persistsTokenAndReturnsExpiry() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        when(bookingRepository.findByBookingRef(REF)).thenReturn(Optional.of(booking));
        when(tokenRepository.findActiveByBookingRefAndType(REF, TokenType.QR))
            .thenReturn(List.of());
        when(tokenRepository.save(any(CheckInToken.class)))
            .thenAnswer(inv -> {
                CheckInToken t = inv.getArgument(0);
                t.setId(1L);
                return t;
            });

        CheckInService.IssueQrResult res = checkInService.issueQrToken(REF, "admin@x");

        ArgumentCaptor<CheckInToken> cap = ArgumentCaptor.forClass(CheckInToken.class);
        verify(tokenRepository).save(cap.capture());
        CheckInToken saved = cap.getValue();
        assertThat(saved.getTokenType()).isEqualTo(TokenType.QR);
        assertThat(saved.getTokenValue()).hasSize(32); // 24 random bytes → 32 chars base64url
        assertThat(saved.getIssuedBy()).isEqualTo("admin@x");
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(47));
        assertThat(res.token()).isEqualTo(saved.getTokenValue());
    }

    @Test
    void issueQrToken_invalidatesPriorActiveTokens() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        CheckInToken prior = CheckInToken.builder()
            .bookingRef(REF).bookingId(1L).tokenType(TokenType.QR)
            .tokenValue("OLD").expiresAt(LocalDateTime.now().plusHours(40)).build();

        when(bookingRepository.findByBookingRef(REF)).thenReturn(Optional.of(booking));
        when(tokenRepository.findActiveByBookingRefAndType(REF, TokenType.QR))
            .thenReturn(List.of(prior));
        when(tokenRepository.save(any(CheckInToken.class))).thenAnswer(inv -> inv.getArgument(0));

        checkInService.issueQrToken(REF, "admin@x");

        // Prior token expiry has been pulled forward to "now"
        assertThat(prior.getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    void issueOtp_storesHashNotPlaintext_andEnqueuesNotification() throws Exception {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        when(bookingRepository.findByBookingRef(REF)).thenReturn(Optional.of(booking));
        when(tokenRepository.findActiveByBookingRefAndType(REF, TokenType.OTP))
            .thenReturn(List.of());
        when(tokenRepository.save(any(CheckInToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        CheckInService.IssueOtpResult res = checkInService.issueOtp(REF, "admin@x");

        ArgumentCaptor<CheckInToken> cap = ArgumentCaptor.forClass(CheckInToken.class);
        verify(tokenRepository).save(cap.capture());
        CheckInToken saved = cap.getValue();

        assertThat(res.otp()).matches("\\d{6}");
        // Stored value must be the SHA-256 of the live OTP, never the OTP itself
        assertThat(saved.getTokenValue()).isEqualTo(sha256(res.otp()));
        assertThat(saved.getTokenValue()).isNotEqualTo(res.otp());

        // Outbox event published for downstream notification
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void issueQr_rejectsCancelledBooking() {
        Booking booking = activeBooking(BookingStatus.CANCELLED);
        when(bookingRepository.findByBookingRef(REF)).thenReturn(Optional.of(booking));
        assertThatThrownBy(() -> checkInService.issueQrToken(REF, "admin@x"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CANCELLED");
        verify(tokenRepository, never()).save(any());
    }

    // ── Verify QR ──────────────────────────────────────────────────────────

    @Test
    void verifyQr_inWindow_consumesTokenAndDelegatesToBookingService() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        // Booking starts now+10min, so we are inside [start-30, start+60]
        booking.setBookingDate(LocalDate.now());
        booking.setStartTime(LocalTime.now().plusMinutes(10).withNano(0));

        CheckInToken token = CheckInToken.builder()
            .id(7L).bookingRef(REF).bookingId(1L).tokenType(TokenType.QR)
            .tokenValue("TOK").expiresAt(LocalDateTime.now().plusHours(2)).build();

        when(tokenRepository.findByValueAndTypeForUpdate("TOK", TokenType.QR))
            .thenReturn(Optional.of(token));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        when(systemSettingsService.getOperationalDate(eq(99L), any())).thenReturn(LocalDate.now());
        BookingDto dto = new BookingDto();
        dto.setStatus(BookingStatus.CHECKED_IN);
        when(bookingService.updateBooking(eq(REF), any(UpdateBookingRequest.class))).thenReturn(dto);

        BookingDto result = checkInService.verifyQr("TOK", 5L, "admin@x", LocalDate.now());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(token.isConsumed()).isTrue();
        assertThat(token.getConsumedBy()).isEqualTo("admin@x");

        // Audit row appended with CHECKED_IN
        verify(eventLogService).logEventFull(any(Booking.class),
            eq(BookingEventType.CHECKED_IN), eq("CONFIRMED"),
            eq(5L), eq("ADMIN"), eq("admin@x"),
            eq("Checked in via QR"),
            any(), any(), any());
    }

    @Test
    void verifyQr_consumedToken_isRejected() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        booking.setBookingDate(LocalDate.now());
        booking.setStartTime(LocalTime.now().plusMinutes(10).withNano(0));

        CheckInToken token = CheckInToken.builder()
            .bookingRef(REF).bookingId(1L).tokenType(TokenType.QR)
            .tokenValue("TOK").expiresAt(LocalDateTime.now().plusHours(2))
            .consumedAt(LocalDateTime.now().minusMinutes(5)).build();

        when(tokenRepository.findByValueAndTypeForUpdate("TOK", TokenType.QR))
            .thenReturn(Optional.of(token));
        // bookingRepository may or may not be invoked depending on guard ordering — make stubs lenient
        lenient().when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> checkInService.verifyQr("TOK", 5L, "admin@x", LocalDate.now()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been used");
        verify(bookingService, never()).updateBooking(anyString(), any());
    }

    @Test
    void verifyQr_outsideWindow_isRejected() {
        // Pick a future LocalDateTime 5h ahead and *carry the date forward* if it
        // wraps past midnight — otherwise LocalTime.now().plusHours(5) silently
        // wraps to early morning of the same day and the production code
        // (correctly) reports it as a closed window rather than a not-yet-open
        // one. We want "way too early", i.e. before windowStart.
        LocalDateTime startDt = LocalDateTime.now().plusHours(5).withNano(0);
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        booking.setBookingDate(startDt.toLocalDate());
        booking.setStartTime(startDt.toLocalTime());

        CheckInToken token = CheckInToken.builder()
            .bookingRef(REF).bookingId(1L).tokenType(TokenType.QR)
            .tokenValue("TOK").expiresAt(LocalDateTime.now().plusHours(48)).build();

        when(tokenRepository.findByValueAndTypeForUpdate("TOK", TokenType.QR))
            .thenReturn(Optional.of(token));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        // Operational-day stub must match the booking date we set above so the
        // op-day guard passes and we reach the window check we're testing.
        when(systemSettingsService.getOperationalDate(eq(99L), any()))
            .thenReturn(startDt.toLocalDate());

        assertThatThrownBy(() -> checkInService.verifyQr("TOK", 5L, "admin@x", LocalDate.now()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Check-in opens at");
        verify(bookingService, never()).updateBooking(anyString(), any());
        assertThat(token.isConsumed()).isFalse();
    }

    // ── Verify OTP ─────────────────────────────────────────────────────────

    @Test
    void verifyOtp_wrongCode_incrementsAttempts_thenLocksAfterMax() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        booking.setBookingDate(LocalDate.now());
        booking.setStartTime(LocalTime.now().plusMinutes(10).withNano(0));

        String correct = "654321";
        CheckInToken token = CheckInToken.builder()
            .bookingRef(REF).bookingId(1L).tokenType(TokenType.OTP)
            .tokenValue(sha256(correct))
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .failedAttempts(0).build();

        when(tokenRepository.findActiveByBookingRefForUpdate(REF, TokenType.OTP))
            .thenReturn(List.of(token));

        // 4 wrong attempts increment counter without locking
        for (int i = 1; i <= 4; i++) {
            int attemptNum = i;
            assertThatThrownBy(() -> checkInService.verifyOtp(REF, "000000", 5L, "a@x", LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Incorrect OTP");
            assertThat(token.getFailedAttempts()).isEqualTo(attemptNum);
        }
        // 5th wrong attempt locks the OTP
        assertThatThrownBy(() -> checkInService.verifyOtp(REF, "000000", 5L, "a@x", LocalDate.now()))
            .hasMessageContaining("Too many incorrect attempts");
        assertThat(token.getFailedAttempts()).isEqualTo(5);
        // Lock pulls expiry forward to "now" — assert it's no later than the present moment
        assertThat(token.getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
        verify(bookingService, never()).updateBooking(anyString(), any());
    }

    @Test
    void verifyOtp_correctCode_checksInBooking() {
        Booking booking = activeBooking(BookingStatus.CONFIRMED);
        booking.setBookingDate(LocalDate.now());
        booking.setStartTime(LocalTime.now().plusMinutes(10).withNano(0));

        String code = "123456";
        CheckInToken token = CheckInToken.builder()
            .bookingRef(REF).bookingId(1L).tokenType(TokenType.OTP)
            .tokenValue(sha256(code))
            .expiresAt(LocalDateTime.now().plusMinutes(10)).build();

        when(tokenRepository.findActiveByBookingRefForUpdate(REF, TokenType.OTP))
            .thenReturn(List.of(token));
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));
        when(systemSettingsService.getOperationalDate(eq(99L), any())).thenReturn(LocalDate.now());
        BookingDto dto = new BookingDto();
        dto.setStatus(BookingStatus.CHECKED_IN);
        when(bookingService.updateBooking(eq(REF), any(UpdateBookingRequest.class))).thenReturn(dto);

        BookingDto result = checkInService.verifyOtp(REF, code, 5L, "a@x", LocalDate.now());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(token.isConsumed()).isTrue();
        verify(bookingService, times(1)).updateBooking(eq(REF), any(UpdateBookingRequest.class));
    }

    @Test
    void verifyOtp_noActiveToken_isRejected() {
        when(tokenRepository.findActiveByBookingRefForUpdate(REF, TokenType.OTP))
            .thenReturn(List.of());
        assertThatThrownBy(() -> checkInService.verifyOtp(REF, "123456", 5L, "a@x", LocalDate.now()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No active OTP");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Booking activeBooking(BookingStatus status) {
        Booking b = new Booking();
        b.setId(1L);
        b.setBookingRef(REF);
        b.setBingeId(99L);
        b.setStatus(status);
        b.setBookingDate(LocalDate.now());
        b.setStartTime(LocalTime.of(18, 0));
        b.setCustomerName("Alice");
        b.setCustomerEmail("alice@example.com");
        return b;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
