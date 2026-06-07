package com.skbingegalaxy.notification.config;

import com.skbingegalaxy.notification.provider.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
    @Profile("!production")
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock")
    public SmsProvider mockSmsProvider() {
        log.warn("Registering MOCK SMS provider — DEV/TEST ONLY, no real SMS will be sent");
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
    @Profile("!production")
    @ConditionalOnProperty(name = "app.whatsapp.provider", havingValue = "mock")
    public WhatsAppProvider mockWhatsAppProvider() {
        log.warn("Registering MOCK WhatsApp provider — DEV/TEST ONLY, no real messages will be sent");
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
    @Profile("!production")
    @ConditionalOnProperty(name = "app.push.provider", havingValue = "mock")
    public PushProvider mockPushProvider() {
        log.warn("Registering MOCK push provider — DEV/TEST ONLY, no real push notifications will be sent");
        return new MockPushProvider();
    }
}
