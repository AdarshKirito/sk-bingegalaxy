package com.skbingegalaxy.notification.provider;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

/**
 * DEV / TEST only — never deploy with app.push.provider=mock in production.
 * ProviderConfig guards this bean with @Profile("!production").
 */
@Slf4j
public class MockPushProvider implements PushProvider {

    @Override
    public String send(String deviceToken, String title, String body, Map<String, String> data) {
        String fakeId = "MOCK-PUSH-" + UUID.randomUUID().toString().substring(0, 8);
        log.warn("[MOCK-PUSH] *** DEV/TEST ONLY — no real push sent *** token={}… id={} title=\"{}\"",
                deviceToken.substring(0, Math.min(12, deviceToken.length())), fakeId, title);
        Metrics.counter("notification.mock.send", "provider", "push").increment();
        return fakeId;
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
