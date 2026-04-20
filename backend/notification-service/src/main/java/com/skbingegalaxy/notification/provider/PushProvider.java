package com.skbingegalaxy.notification.provider;

import java.util.Map;

/**
 * Abstraction over push-notification backends (FCM, APNs, …).
 */
public interface PushProvider {

    String send(String deviceToken, String title, String body, Map<String, String> data);

    /**
     * Send a rich push notification with optional image, deep link, and action buttons.
     * Default implementation delegates to the basic send method.
     */
    default String sendRich(String deviceToken, String title, String body,
                            Map<String, String> data, String imageUrl,
                            String deepLinkUrl, Map<String, String> actionButtons) {
        // Enrich data map with rich payload fields for providers that read from data
        if (imageUrl != null && !imageUrl.isBlank()) data.put("imageUrl", imageUrl);
        if (deepLinkUrl != null && !deepLinkUrl.isBlank()) data.put("deepLinkUrl", deepLinkUrl);
        if (actionButtons != null) {
            actionButtons.forEach((k, v) -> data.put("action_" + k, v));
        }
        return send(deviceToken, title, body, data);
    }

    String providerName();
}
