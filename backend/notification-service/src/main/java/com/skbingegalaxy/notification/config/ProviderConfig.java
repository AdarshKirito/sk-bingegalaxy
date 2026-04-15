package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.notification.provider.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ProviderConfig {

    // ── SMS ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")
    public SmsProvider twilioSmsProvider(
            @Value("${app.twilio.account-sid}") String sid,
            @Value("${app.twilio.auth-token}") String token,
            @Value("${app.twilio.sms-from}") String from) {
        log.info("Registering Twilio SMS provider");
        return new TwilioSmsProvider(sid, token, from);
    }

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock")
    public SmsProvider mockSmsProvider() {
        log.info("Registering mock SMS provider");
        return new MockSmsProvider();
    }

    // ── WhatsApp ─────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "app.whatsapp.provider", havingValue = "twilio")
    public WhatsAppProvider twilioWhatsAppProvider(
            @Value("${app.twilio.account-sid}") String sid,
            @Value("${app.twilio.auth-token}") String token,
            @Value("${app.twilio.whatsapp-from}") String from) {
        log.info("Registering Twilio WhatsApp provider");
        return new TwilioWhatsAppProvider(sid, token, from);
    }

    @Bean
    @ConditionalOnProperty(name = "app.whatsapp.provider", havingValue = "mock")
    public WhatsAppProvider mockWhatsAppProvider() {
        log.info("Registering mock WhatsApp provider");
        return new MockWhatsAppProvider();
    }

    // ── Push (FCM) ───────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "app.push.provider", havingValue = "fcm")
    public PushProvider fcmPushProvider(
            @Value("${app.fcm.credentials-path}") String credPath) {
        log.info("Registering FCM push provider");
        return new FcmPushProvider(credPath);
    }

    @Bean
    @ConditionalOnProperty(name = "app.push.provider", havingValue = "mock")
    public PushProvider mockPushProvider() {
        log.info("Registering mock push provider");
        return new MockPushProvider();
    }
}
