package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private TemplateEngine templateEngine;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // mailSender is null (not configured) — emails are logged only
        notificationService = new NotificationService(notificationRepository, null, templateEngine);
        ReflectionTestUtils.setField(notificationService, "fromEmail", "test@skbingegalaxy.com");
        ReflectionTestUtils.setField(notificationService, "maxRetries", 3);
    }

    // ══════════════════════════════════════════════════════
    //  SEND NOTIFICATION
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendNotification")
    class SendNotificationTests {

        @Test
        @DisplayName("Creates and saves notification with correct fields")
        void sendsNotification_savesCorrectly() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(i -> {
                        Notification n = i.getArgument(0);
                        n.setId("notif-1");
                        return n;
                    });

            NotificationDto result = notificationService.sendNotification(
                    "BOOKING_CREATED", NotificationChannel.EMAIL,
                    "john@example.com", "9876543210", "John Doe",
                    "Booking Confirmed", "Your booking is confirmed.",
                    "SKBG25123456", Map.of("eventType", "Birthday"));

            assertThat(result.getType()).isEqualTo("BOOKING_CREATED");
            assertThat(result.getRecipientEmail()).isEqualTo("john@example.com");
            assertThat(result.getBookingRef()).isEqualTo("SKBG25123456");
            assertThat(result.getSubject()).isEqualTo("Booking Confirmed");

            // 2 saves: initial + post-dispatch
            verify(notificationRepository, times(2)).save(any(Notification.class));
        }

        @Test
        @DisplayName("Email dispatch succeeds when mailSender is null (logged)")
        void emailWithNullMailSender_markedAsSent() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(i -> {
                        Notification n = i.getArgument(0);
                        n.setId("notif-2");
                        return n;
                    });

            NotificationDto result = notificationService.sendNotification(
                    "PAYMENT_SUCCESS", NotificationChannel.EMAIL,
                    "john@example.com", null, "John",
                    "Payment OK", "Paid.", null, null);

            // With null mailSender, sendEmail logs and returns (no exception → success)
            assertThat(result.isSent()).isTrue();
        }

        @Test
        @DisplayName("SMS notification dispatches correctly")
        void smsNotification_dispatches() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(i -> {
                        Notification n = i.getArgument(0);
                        n.setId("notif-3");
                        return n;
                    });

            NotificationDto result = notificationService.sendNotification(
                    "BOOKING_CREATED", NotificationChannel.SMS,
                    null, "9876543210", "John",
                    null, "Your booking is confirmed.", "SKBG001", null);

            assertThat(result.isSent()).isTrue();
            assertThat(result.getChannel()).isEqualTo(NotificationChannel.SMS);
        }

        @Test
        @DisplayName("WhatsApp notification dispatches correctly")
        void whatsappNotification_dispatches() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(i -> {
                        Notification n = i.getArgument(0);
                        n.setId("notif-4");
                        return n;
                    });

            NotificationDto result = notificationService.sendNotification(
                    "BOOKING_CREATED", NotificationChannel.WHATSAPP,
                    null, "9876543210", "John",
                    null, "Booking confirmed!", "SKBG002", null);

            assertThat(result.isSent()).isTrue();
            assertThat(result.getChannel()).isEqualTo(NotificationChannel.WHATSAPP);
        }

        @Test
        @DisplayName("Metadata is preserved in notification")
        void metadataPreserved() {
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(i -> {
                        Notification n = i.getArgument(0);
                        n.setId("notif-5");
                        return n;
                    });

            Map<String, Object> meta = Map.of("amount", "5000", "transactionId", "TXN-123");

            NotificationDto result = notificationService.sendNotification(
                    "PAYMENT_SUCCESS", NotificationChannel.EMAIL,
                    "john@example.com", null, "John",
                    "Payment OK", "Paid.", "SKBG003", meta);

            assertThat(result.getMetadata()).containsEntry("amount", "5000");
            assertThat(result.getMetadata()).containsEntry("transactionId", "TXN-123");
        }
    }

    // ══════════════════════════════════════════════════════
    //  QUERY METHODS
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("Query methods")
    class QueryTests {

        @Test
        @DisplayName("getByEmail returns notifications for email")
        void getByEmail_returnsNotifications() {
            Notification n = Notification.builder()
                    .id("n1").recipientEmail("john@example.com").type("BOOKING_CREATED")
                    .channel(NotificationChannel.EMAIL).subject("Test").body("Body")
                    .sent(true).retryCount(0).createdAt(LocalDateTime.now()).build();

            when(notificationRepository.findByRecipientEmailOrderByCreatedAtDesc("john@example.com"))
                    .thenReturn(List.of(n));

            List<NotificationDto> result = notificationService.getByEmail("john@example.com");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo("BOOKING_CREATED");
        }

        @Test
        @DisplayName("getByBookingRef returns notifications for booking")
        void getByBookingRef_returnsNotifications() {
            Notification n = Notification.builder()
                    .id("n2").bookingRef("SKBG001").type("PAYMENT_SUCCESS")
                    .channel(NotificationChannel.EMAIL).subject("Payment").body("OK")
                    .sent(true).retryCount(0).createdAt(LocalDateTime.now()).build();

            when(notificationRepository.findByBookingRefOrderByCreatedAtDesc("SKBG001"))
                    .thenReturn(List.of(n));

            List<NotificationDto> result = notificationService.getByBookingRef("SKBG001");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBookingRef()).isEqualTo("SKBG001");
        }

        @Test
        @DisplayName("getByEmail with no results returns empty list")
        void getByEmail_noResults_returnsEmpty() {
            when(notificationRepository.findByRecipientEmailOrderByCreatedAtDesc("nobody@example.com"))
                    .thenReturn(List.of());

            List<NotificationDto> result = notificationService.getByEmail("nobody@example.com");

            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════
    //  RETRY FAILED NOTIFICATIONS
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("retryFailedNotifications")
    class RetryTests {

        @Test
        @DisplayName("Retries failed notifications under max retries")
        void retriesFailed_underMaxRetries() {
            Notification failed = Notification.builder()
                    .id("f1").recipientEmail("john@example.com")
                    .type("BOOKING_CREATED").channel(NotificationChannel.EMAIL)
                    .subject("Test").body("Body").sent(false).retryCount(1).build();

            when(notificationRepository.findBySentFalseAndRetryCountLessThan(3))
                    .thenReturn(List.of(failed));

            notificationService.retryFailedNotifications();

            assertThat(failed.getRetryCount()).isEqualTo(2);
            assertThat(failed.isSent()).isTrue(); // mailSender=null → logs only → success
            verify(notificationRepository).save(failed);
        }

        @Test
        @DisplayName("No failed notifications does nothing")
        void noFailed_doesNothing() {
            when(notificationRepository.findBySentFalseAndRetryCountLessThan(3))
                    .thenReturn(List.of());

            notificationService.retryFailedNotifications();

            verify(notificationRepository, never()).save(any());
        }
    }
}
