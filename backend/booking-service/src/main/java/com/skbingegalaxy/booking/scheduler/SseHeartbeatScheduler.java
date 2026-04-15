package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.config.AdminEventBus;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends SSE keepalive heartbeats every 30 seconds so that proxies and browsers
 * do not close the idle connection prematurely.
 */
@Component
@RequiredArgsConstructor
public class SseHeartbeatScheduler {

    private final AdminEventBus eventBus;

    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        eventBus.heartbeat();
    }
}
