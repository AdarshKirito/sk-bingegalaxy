package com.skbingegalaxy.notification.provider;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class MockSmsProvider implements SmsProvider {

    @Override
    public String send(String toPhone, String body) {
        String fakeId = "MOCK-SMS-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[MOCK-SMS] to={} id={} body=\"{}\"", toPhone, fakeId, body.length() > 80 ? body.substring(0, 80) + "…" : body);
        return fakeId;
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
