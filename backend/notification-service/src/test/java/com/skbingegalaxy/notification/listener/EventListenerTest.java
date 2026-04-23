package com.skbingegalaxy.notification.listener;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.repository.BookingReminderRepository;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import com.skbingegalaxy.notification.service.ChannelRouter;
import com.skbingegalaxy.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventListenerTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private BookingReminderRepository bookingReminderRepository;
    @Mock private ChannelRouter channelRouter;
    @InjectMocks private EventListener eventListener;

    @BeforeEach
    void setUp() {
        // Default: channel router always returns EMAIL (matches existing test expectations)
        lenient().when(channelRouter.resolveChannel(any(), any(), any(), any()))
                .thenReturn(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("Booking created event triggers email notification")
    void handleBookingCreated_sendsNotification() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG001")
                .customerId(1L)
                .customerName("John Doe")
                .customerEmail("john@example.com")
                .customerPhone("9876543210")
                .eventTypeName("Birthday Party")
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .totalAmount(BigDecimal.valueOf(5000))
                .status("PENDING")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n1").build());

        eventListener.handleBookingCreated(event);

        verify(notificationService).sendNotification(
                eq("BOOKING_CREATED"),
                eq(NotificationChannel.EMAIL),
                eq("john@example.com"),
                eq("9876543210"),
                eq("John Doe"),
                contains("SKBG001"),
                contains("Birthday Party"),
                eq("SKBG001"),
                any());
    }

    @Test
    @DisplayName("Booking created event skips duplicate notifications only when a recent sent notification exists")
    void handleBookingCreated_recentSuccessfulDuplicate_skipsNotification() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG001")
                .customerName("John Doe")
                .customerEmail("john@example.com")
                .build();

        when(notificationRepository.existsByBookingRefAndTypeAndSentTrueAndCreatedAtAfter(
                eq("SKBG001"), eq("BOOKING_CREATED"), any(java.time.LocalDateTime.class)))
                .thenReturn(true);

        eventListener.handleBookingCreated(event);

        verify(notificationService, never()).sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Booking cancelled event triggers notification")
    void handleBookingCancelled_sendsNotification() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG002")
                .customerName("Jane Doe")
                .customerEmail("jane@example.com")
                .eventTypeName("Movie Night")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n2").build());

        eventListener.handleBookingCancelled(event);

        verify(notificationService).sendNotification(
                eq("BOOKING_CANCELLED"),
                eq(NotificationChannel.EMAIL),
                eq("jane@example.com"),
                any(),
                eq("Jane Doe"),
                contains("SKBG002"),
                any(),
                eq("SKBG002"),
                any());
    }

    @Test
    @DisplayName("Payment success event triggers notification")
    void handlePaymentSuccess_sendsNotification() {
        PaymentEvent event = PaymentEvent.builder()
                .bookingRef("SKBG003")
                .transactionId("TXN-123")
                .amount(BigDecimal.valueOf(5000))
                .paymentMethod("UPI")
                .customerEmail("john@example.com")
                .customerName("John Doe")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n3").build());

        eventListener.handlePaymentSuccess(event);

        verify(notificationService).sendNotification(
                eq("PAYMENT_SUCCESS"),
                eq(NotificationChannel.EMAIL),
                eq("john@example.com"), isNull(), eq("John Doe"),
                contains("SKBG003"),
                any(),
                eq("SKBG003"),
                any());
    }

    @Test
    @DisplayName("Payment failed event triggers notification")
    void handlePaymentFailed_sendsNotification() {
        PaymentEvent event = PaymentEvent.builder()
                .bookingRef("SKBG004")
                .transactionId("TXN-456")
                .customerEmail("john@example.com")
                .customerName("John Doe")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n4").build());

        eventListener.handlePaymentFailed(event);

        verify(notificationService).sendNotification(
                eq("PAYMENT_FAILED"),
                eq(NotificationChannel.EMAIL),
                eq("john@example.com"), isNull(), eq("John Doe"),
                contains("SKBG004"),
                any(),
                eq("SKBG004"),
                any());
    }

    @Test
    @DisplayName("Direct notification event uses correct channel")
    void handleDirectNotification_usesCorrectChannel() {
        NotificationEvent event = NotificationEvent.builder()
                .type("CUSTOM")
                .channel(NotificationChannel.SMS)
                .recipientEmail("john@example.com")
                .recipientPhone("9876543210")
                .recipientName("John")
                .subject("Custom Subject")
                .body("Custom body")
                .bookingRef("SKBG005")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n5").build());

        eventListener.handleDirectNotification(event);

        verify(notificationService).sendNotification(
                eq("CUSTOM"),
                eq(NotificationChannel.SMS),
                eq("john@example.com"),
                eq("9876543210"),
                eq("John"),
                eq("Custom Subject"),
                eq("Custom body"),
                eq("SKBG005"),
                isNull());
    }

    @Test
    @DisplayName("Direct notification with null channel defaults to EMAIL")
    void handleDirectNotification_nullChannel_defaultsToEmail() {
        NotificationEvent event = NotificationEvent.builder()
                .type("INFO")
                .channel(null)
                .recipientEmail("admin@example.com")
                .subject("Test")
                .body("Body")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n6").build());

        eventListener.handleDirectNotification(event);

        verify(notificationService).sendNotification(
                eq("INFO"),
                eq(NotificationChannel.EMAIL),
                eq("admin@example.com"),
                isNull(),
                isNull(),
                eq("Test"),
                eq("Body"),
                isNull(),
                isNull());
    }

    @Test
    @DisplayName("User registered event sends welcome notification")
    void handleUserRegistered_sendsWelcome() {
        NotificationEvent event = NotificationEvent.builder()
                .recipientEmail("newuser@example.com")
                .recipientPhone("9876543210")
                .recipientName("New User")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n7").build());

        eventListener.handleUserRegistered(event);

        verify(notificationService).sendNotification(
                eq("USER_REGISTERED"),
                eq(NotificationChannel.EMAIL),
                eq("newuser@example.com"),
                eq("9876543210"),
                eq("New User"),
                eq("Welcome to SK Binge Galaxy!"),
                contains("New User"),
                isNull(),
                isNull());
    }

    @Test
    @DisplayName("Password reset event sends OTP notification")
    void handlePasswordReset_sendsOtpNotification() {
        NotificationEvent event = NotificationEvent.builder()
                .recipientEmail("john@example.com")
                .recipientName("John")
                .body("Your OTP is 123456")
                .metadata(Map.of("otp", "123456"))
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("n8").build());

        eventListener.handlePasswordReset(event);

        verify(notificationService).sendNotification(
                eq("PASSWORD_RESET"),
                eq(NotificationChannel.EMAIL),
                eq("john@example.com"),
                isNull(),
                eq("John"),
                contains("Password Reset"),
                eq("Your OTP is 123456"),
                isNull(),
                any());
    }

    // ── Regression tests for DLT/retry behaviour ──────────────────────────────
    // These verify that unexpected downstream failures are re-thrown so Spring's
    // DefaultErrorHandler can retry and eventually route the record to the DLT.
    // Swallowing exceptions (the previous behaviour) silently lost notifications.

    @Test
    @DisplayName("BOOKING_CREATED: rethrows when sendNotification fails so DLT receives the record")
    void handleBookingCreated_downstreamFailure_rethrows() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG-DLT-1")
                .customerName("X")
                .customerEmail("x@example.com")
                .eventTypeName("E")
                .bookingDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(10, 0))
                .durationHours(1)
                .totalAmount(BigDecimal.ONE)
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handleBookingCreated(event));
    }

    @Test
    @DisplayName("BOOKING_CANCELLED: rethrows when sendNotification fails")
    void handleBookingCancelled_downstreamFailure_rethrows() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG-DLT-2")
                .customerName("X")
                .customerEmail("x@example.com")
                .eventTypeName("E")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handleBookingCancelled(event));
    }

    @Test
    @DisplayName("PAYMENT_SUCCESS: rethrows when sendNotification fails")
    void handlePaymentSuccess_downstreamFailure_rethrows() {
        PaymentEvent event = PaymentEvent.builder()
                .bookingRef("SKBG-DLT-3")
                .customerEmail("x@example.com")
                .customerName("X")
                .transactionId("T")
                .amount(BigDecimal.ONE)
                .paymentMethod("UPI")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handlePaymentSuccess(event));
    }

    @Test
    @DisplayName("PAYMENT_FAILED: rethrows when sendNotification fails")
    void handlePaymentFailed_downstreamFailure_rethrows() {
        PaymentEvent event = PaymentEvent.builder()
                .bookingRef("SKBG-DLT-4")
                .customerEmail("x@example.com")
                .customerName("X")
                .transactionId("T")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handlePaymentFailed(event));
    }

    @Test
    @DisplayName("NOTIFICATION_SEND: rethrows when sendNotification fails")
    void handleDirectNotification_downstreamFailure_rethrows() {
        NotificationEvent event = NotificationEvent.builder()
                .type("CUSTOM")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail("x@example.com")
                .subject("S")
                .body("B")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handleDirectNotification(event));
    }

    @Test
    @DisplayName("USER_REGISTERED: rethrows when sendNotification fails")
    void handleUserRegistered_downstreamFailure_rethrows() {
        NotificationEvent event = NotificationEvent.builder()
                .recipientEmail("x@example.com")
                .recipientName("X")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handleUserRegistered(event));
    }

    @Test
    @DisplayName("PASSWORD_RESET: rethrows when sendNotification fails")
    void handlePasswordReset_downstreamFailure_rethrows() {
        NotificationEvent event = NotificationEvent.builder()
                .recipientEmail("x@example.com")
                .recipientName("X")
                .body("OTP")
                .build();

        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> eventListener.handlePasswordReset(event));
    }

    @Test
    @DisplayName("Business skip (missing email) still returns normally without throwing")
    void handleBookingCreated_missingEmail_doesNotThrow() {
        BookingEvent event = BookingEvent.builder()
                .bookingRef("SKBG-SKIP-1")
                .customerEmail(null)   // missing — should be skipped silently, not rethrown
                .build();

        eventListener.handleBookingCreated(event);

        verify(notificationService, never()).sendNotification(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
