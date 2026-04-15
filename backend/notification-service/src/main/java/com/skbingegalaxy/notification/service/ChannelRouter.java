package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.notification.provider.PushProvider;
import com.skbingegalaxy.notification.provider.SmsProvider;
import com.skbingegalaxy.notification.provider.WhatsAppProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Resolves the best notification channel for a given recipient
 * using a cascading priority: PUSH → SMS → EMAIL.
 *
 * <p>The router checks:
 * <ol>
 *   <li>Whether the channel's provider is configured</li>
 *   <li>Whether the recipient has the necessary contact info (device token, phone, email)</li>
 *   <li>Whether the user has muted the channel via preferences</li>
 * </ol>
 */
@Service
@Slf4j
public class ChannelRouter {

    /** Notification types that must always go via EMAIL (security-critical). */
    private static final Set<String> EMAIL_ONLY_TYPES = Set.of("PASSWORD_RESET");

    private final NotificationPreferenceService preferenceService;
    private final SmsProvider smsProvider;
    private final WhatsAppProvider whatsAppProvider;
    private final PushProvider pushProvider;

    public ChannelRouter(
            NotificationPreferenceService preferenceService,
            @Autowired(required = false) SmsProvider smsProvider,
            @Autowired(required = false) WhatsAppProvider whatsAppProvider,
            @Autowired(required = false) PushProvider pushProvider) {
        this.preferenceService = preferenceService;
        this.smsProvider = smsProvider;
        this.whatsAppProvider = whatsAppProvider;
        this.pushProvider = pushProvider;
    }

    /**
     * Resolve the best channel for the given recipient and notification type.
     *
     * @param recipientEmail user's email (always available)
     * @param recipientPhone user's phone number (may be null)
     * @param type           notification type key (e.g. "BOOKING_CREATED")
     * @param metadata       notification metadata — may contain "deviceToken"
     * @return the best available {@link NotificationChannel}
     */
    public NotificationChannel resolveChannel(
            String recipientEmail,
            String recipientPhone,
            String type,
            Map<String, Object> metadata) {

        // Security-critical notifications always go via email
        if (EMAIL_ONLY_TYPES.contains(type)) {
            return NotificationChannel.EMAIL;
        }

        String deviceToken = extractDeviceToken(metadata);

        // Priority 1: PUSH — if provider configured, device token present, and not muted
        if (pushProvider != null
                && deviceToken != null && !deviceToken.isBlank()
                && !isMuted(recipientEmail, type, NotificationChannel.PUSH)) {
            log.debug("Channel routing → PUSH for type={} email={}", type, recipientEmail);
            return NotificationChannel.PUSH;
        }

        // Priority 2: SMS — if provider configured, phone present, and not muted
        if (smsProvider != null
                && recipientPhone != null && !recipientPhone.isBlank()
                && !isMuted(recipientEmail, type, NotificationChannel.SMS)) {
            log.debug("Channel routing → SMS for type={} email={}", type, recipientEmail);
            return NotificationChannel.SMS;
        }

        // Priority 3 (fallback): EMAIL
        log.debug("Channel routing → EMAIL (fallback) for type={} email={}", type, recipientEmail);
        return NotificationChannel.EMAIL;
    }

    private boolean isMuted(String email, String type, NotificationChannel channel) {
        if (email == null) return false;
        return preferenceService.isSuppressed(email, type, channel.name());
    }

    private String extractDeviceToken(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object token = metadata.get("deviceToken");
        return token != null ? token.toString() : null;
    }
}
