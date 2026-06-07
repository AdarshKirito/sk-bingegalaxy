package com.skbingegalaxy.notification.provider;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * DEV / TEST only — never deploy with app.sms.provider=mock in production.
 * ProviderConfig guards this bean with @Profile("!production").
 */
@Slf4j
public class MockSmsProvider implements SmsProvider {

    @Override
    public String send(String toPhone, String body) {
        String fakeId = "MOCK-SMS-" + UUID.randomUUID().toString().substring(0, 8);
        log.warn("[MOCK-SMS] *** DEV/TEST ONLY — no real SMS sent *** to={} id={} body=\"{}\"",
                toPhone, fakeId, body.length() > 80 ? body.substring(0, 80) + "…" : body);
        Metrics.counter("notification.mock.send", "provider", "sms").increment();
        return fakeId;
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
