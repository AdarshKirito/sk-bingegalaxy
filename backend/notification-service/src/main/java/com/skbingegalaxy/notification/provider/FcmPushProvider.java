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
        try {
            Message.Builder builder = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());
            if (data != null && !data.isEmpty()) {
                builder.putAllData(data);
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
