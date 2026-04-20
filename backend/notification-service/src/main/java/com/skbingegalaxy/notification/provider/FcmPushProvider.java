package com.skbingegalaxy.notification.provider;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

@Slf4j
public class FcmPushProvider implements PushProvider {

    private final String credentialsPath;

    public FcmPushProvider(String credentialsPath) {
        this.credentialsPath = credentialsPath;
    }

    @PostConstruct
    void init() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(new FileInputStream(credentialsPath)))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase FCM provider initialised");
        }
    }

    @Override
    public String send(String deviceToken, String title, String body, Map<String, String> data) {
        return sendRich(deviceToken, title, body, data, null, null, null);
    }

    @Override
    public String sendRich(String deviceToken, String title, String body,
                           Map<String, String> data, String imageUrl,
                           String deepLinkUrl, Map<String, String> actionButtons) {
        try {
            Notification.Builder notifBuilder = Notification.builder()
                    .setTitle(title)
                    .setBody(body);
            if (imageUrl != null && !imageUrl.isBlank()) {
                notifBuilder.setImage(imageUrl);
            }

            Message.Builder builder = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(notifBuilder.build());

            // Merge all data
            Map<String, String> allData = new java.util.HashMap<>();
            if (data != null) allData.putAll(data);
            if (deepLinkUrl != null && !deepLinkUrl.isBlank()) allData.put("deepLinkUrl", deepLinkUrl);
            if (actionButtons != null) {
                actionButtons.forEach((k, v) -> allData.put("action_" + k, v));
            }
            if (!allData.isEmpty()) {
                builder.putAllData(allData);
            }

            String messageId = FirebaseMessaging.getInstance().send(builder.build());
            log.info("Push sent via FCM to token={}… — id={}", deviceToken.substring(0, Math.min(12, deviceToken.length())), messageId);
            return messageId;
        } catch (Exception e) {
            throw new RuntimeException("FCM push failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "fcm";
    }
}
