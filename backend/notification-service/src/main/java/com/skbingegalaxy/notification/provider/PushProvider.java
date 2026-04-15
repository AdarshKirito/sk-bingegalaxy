package com.skbingegalaxy.notification.provider;

/**
 * Abstraction over push-notification backends (FCM, APNs, …).
 */
public interface PushProvider {

    String send(String deviceToken, String title, String body, java.util.Map<String, String> data);

    String providerName();
}
