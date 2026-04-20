package com.skbingegalaxy.booking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory event bus that pushes admin-relevant events to connected
 * Server-Sent Event (SSE) clients, scoped by binge (multi-tenant).
 *
 * <p>Each admin subscribes to a specific bingeId channel so they only
 * receive events for the venue they are managing.</p>
 *
 * <p>Thread-safety: The outer {@code ConcurrentHashMap} is safe for concurrent
 * get/put. The inner {@code CopyOnWriteArrayList} is safe for concurrent
 * iteration, but remove-during-iteration only skips, never corrupts.
 * We collect dead emitters into a separate list and remove after iteration
 * to avoid ConcurrentModificationException on compound operations.</p>
 */
@Component
@Slf4j
public class AdminEventBus {

    /** bingeId → list of SSE emitters for that binge */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> channels = new ConcurrentHashMap<>();

    /**
     * Subscribe an admin client to a specific binge channel.
     * Timeout set to 5 minutes; the client is expected to reconnect.
     */
    public SseEmitter subscribe(Long bingeId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        CopyOnWriteArrayList<SseEmitter> list = channels.computeIfAbsent(bingeId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> {
            list.remove(emitter);
            if (list.isEmpty()) channels.remove(bingeId, list);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE emitter error for binge {}: {}", bingeId, e.getMessage());
            cleanup.run();
        });
        log.debug("Admin SSE client connected for binge {}. Channel size: {}", bingeId, list.size());
        return emitter;
    }

    /**
     * Publish an event to all admin clients subscribed to the given binge.
     *
     * @param bingeId   the binge channel to target
     * @param eventType e.g. "booking", "payment"
     * @param payload   serializable data (will be JSON-encoded by Spring)
     */
    public void publish(Long bingeId, String eventType, Object payload) {
        if (bingeId == null) return;
        CopyOnWriteArrayList<SseEmitter> list = channels.get(bingeId);
        if (list == null || list.isEmpty()) return;

        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventType)
                .data(payload);

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                log.debug("Publish failed for binge {} emitter ({}), marking for removal", bingeId, e.getClass().getSimpleName());
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            list.removeAll(dead);
            if (list.isEmpty()) channels.remove(bingeId, list);
            log.debug("Removed {} dead SSE emitters for binge {}", dead.size(), bingeId);
        }
    }

    /**
     * Send a heartbeat to all connected clients across all binge channels.
     * Dead emitters are collected and removed per-channel after iteration.
     */
    public void heartbeat() {
        if (channels.isEmpty()) return;

        SseEmitter.SseEventBuilder beat = SseEmitter.event()
                .name("heartbeat")
                .data(Map.of("ts", System.currentTimeMillis()));

        channels.forEach((bingeId, list) -> {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(beat);
                } catch (IOException | IllegalStateException e) {
                    dead.add(emitter);
                }
            }
            if (!dead.isEmpty()) {
                list.removeAll(dead);
                if (list.isEmpty()) channels.remove(bingeId, list);
            }
        });
    }

    /** Count of all connected SSE clients across all binge channels. */
    public int getClientCount() {
        return channels.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }
}
