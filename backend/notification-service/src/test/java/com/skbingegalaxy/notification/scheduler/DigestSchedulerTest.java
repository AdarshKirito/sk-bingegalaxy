package com.skbingegalaxy.notification.scheduler;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.model.Notification;
import com.skbingegalaxy.notification.repository.NotificationRepository;
import com.skbingegalaxy.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DigestSchedulerTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private DigestScheduler digestScheduler;

    @Test
    @DisplayName("sendDigests groups by digestGroup and sends digest email")
    void sendDigests_groupsAndSendsDigestEmail() {
        Notification n1 = Notification.builder()
                .id("n1").recipientEmail("john@example.com").recipientName("John")
                .type("BOOKING_CREATED").channel(NotificationChannel.EMAIL)
                .subject("Booking 1").body("Body 1")
                .sent(true).digested(false).digestGroup("digest:john@example.com")
                .createdAt(LocalDateTime.now()).build();
        Notification n2 = Notification.builder()
                .id("n2").recipientEmail("john@example.com").recipientName("John")
                .type("PAYMENT_SUCCESS").channel(NotificationChannel.EMAIL)
                .subject("Payment 1").body("Body 2")
                .sent(true).digested(false).digestGroup("digest:john@example.com")
                .createdAt(LocalDateTime.now()).build();

        when(notificationRepository.findUndigestedWithDigestGroup())
                .thenReturn(List.of(n1, n2));
        when(notificationService.sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationDto.builder().id("digest-1").build());

        digestScheduler.sendDigests();

        // Verify digest email was sent
        verify(notificationService).sendNotification(
                eq("DIGEST"), eq(NotificationChannel.EMAIL),
                eq("john@example.com"), isNull(), eq("John"),
                contains("2 updates"), any(), isNull(), any());

        // Verify both notifications were batch-saved as digested
        verify(notificationRepository).saveAll(List.of(n1, n2));
    }

    @Test
    @DisplayName("sendDigests does nothing when no undigested notifications exist")
    void sendDigests_noUndigested_doesNothing() {
        when(notificationRepository.findUndigestedWithDigestGroup())
                .thenReturn(List.of());

        digestScheduler.sendDigests();

        verify(notificationService, never())
                .sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("sendDigests skips groups with only one notification")
    void sendDigests_singleNotificationGroup_skipped() {
        Notification solo = Notification.builder()
                .id("n1").recipientEmail("jane@example.com").recipientName("Jane")
                .type("BOOKING_CREATED").channel(NotificationChannel.EMAIL)
                .subject("Solo").body("Body")
                .sent(true).digested(false).digestGroup("digest:jane@example.com")
                .createdAt(LocalDateTime.now()).build();

        when(notificationRepository.findUndigestedWithDigestGroup())
                .thenReturn(List.of(solo));

        digestScheduler.sendDigests();

        verify(notificationService, never())
                .sendNotification(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
