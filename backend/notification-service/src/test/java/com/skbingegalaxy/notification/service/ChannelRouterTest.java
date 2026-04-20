package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.provider.PushProvider;
import com.skbingegalaxy.notification.provider.SmsProvider;
import com.skbingegalaxy.notification.provider.WhatsAppProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelRouterTest {

    @Mock private NotificationPreferenceService preferenceService;
    @Mock private SmsProvider smsProvider;
    @Mock private WhatsAppProvider whatsAppProvider;
    @Mock private PushProvider pushProvider;

    @Test
    @DisplayName("PASSWORD_RESET always routes to EMAIL regardless of providers")
    void passwordReset_alwaysEmail() {
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, pushProvider);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "PASSWORD_RESET",
                Map.of("deviceToken", "tok123"));
        assertThat(channel).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("Routes to PUSH when push provider and device token are available")
    void routesToPush_whenAvailable() {
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, pushProvider);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED",
                Map.of("deviceToken", "tok123"));
        assertThat(channel).isEqualTo(NotificationChannel.PUSH);
    }

    @Test
    @DisplayName("Falls back to WHATSAPP when push is muted")
    void fallsToWhatsApp_whenPushMuted() {
        when(preferenceService.isSuppressed("john@example.com", "BOOKING_CREATED", "PUSH"))
                .thenReturn(true);
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, pushProvider);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED",
                Map.of("deviceToken", "tok123"));
        assertThat(channel).isEqualTo(NotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("Falls back to SMS when push and whatsapp are muted")
    void fallsToSms_whenPushAndWhatsAppMuted() {
        when(preferenceService.isSuppressed("john@example.com", "BOOKING_CREATED", "PUSH"))
                .thenReturn(true);
        when(preferenceService.isSuppressed("john@example.com", "BOOKING_CREATED", "WHATSAPP"))
                .thenReturn(true);
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, pushProvider);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED",
                Map.of("deviceToken", "tok123"));
        assertThat(channel).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("Falls back to EMAIL when all channels are muted")
    void fallsToEmail_whenAllMuted() {
        when(preferenceService.isSuppressed(eq("john@example.com"), eq("BOOKING_CREATED"), anyString()))
                .thenReturn(true);
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, pushProvider);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED",
                Map.of("deviceToken", "tok123"));
        assertThat(channel).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("Falls back to EMAIL when no providers are configured")
    void fallsToEmail_whenNoProviders() {
        ChannelRouter router = new ChannelRouter(preferenceService, null, null, null);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED", null);
        assertThat(channel).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("Routes to WHATSAPP when no push provider but whatsapp is available")
    void routesToWhatsApp_whenNoPushProvider() {
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, null);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED", null);
        assertThat(channel).isEqualTo(NotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("Falls back to SMS when no push or whatsapp provider")
    void fallsToSms_whenNoPushOrWhatsApp() {
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, null, null);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", "9876543210", "BOOKING_CREATED", null);
        assertThat(channel).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("Falls back to EMAIL when phone number is null")
    void fallsToEmail_whenNoPhone() {
        ChannelRouter router = new ChannelRouter(preferenceService, smsProvider, whatsAppProvider, null);
        NotificationChannel channel = router.resolveChannel(
                "john@example.com", null, "BOOKING_CREATED", null);
        assertThat(channel).isEqualTo(NotificationChannel.EMAIL);
    }
}
