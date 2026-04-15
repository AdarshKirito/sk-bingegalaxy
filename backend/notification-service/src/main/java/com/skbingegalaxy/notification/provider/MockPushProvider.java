package com.skbingegalaxy.notification.provider;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

@Slf4j
public class MockPushProvider implements PushProvider {

    @Override
    public String send(String deviceToken, String title, String body, Map<String, String> data) {
        String fakeId = "MOCK-PUSH-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[MOCK-PUSH] token={}… id={} title=\"{}\"", deviceToken.substring(0, Math.min(12, deviceToken.length())), fakeId, title);
        return fakeId;
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
