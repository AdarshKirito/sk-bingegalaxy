package com.skbingegalaxy.booking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory event bus that pushes admin-relevant events to connected
 * Server-Sent Event (SSE) clients.
 *
 * Kafka listeners call {@link #publish(String, Object)} when booking/payment
 * events occur; the SSE controller registers emitters via {@link #subscribe()}.
 */
@Component
@Slf4j
public class AdminEventBus {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Create a new SSE emitter for an admin client.
     * Timeout set to 5 minutes; the client is expected to reconnect.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.debug("Admin SSE client connected. Total: {}", emitters.size());
        return emitter;
    }

    /**
     * Publish an event to all connected admin SSE clients.
     *
     * @param eventType e.g. "booking", "payment"
     * @param payload   serializable data (will be JSON-encoded by Spring)
     */
    public void publish(String eventType, Object payload) {
        if (emitters.isEmpty()) return;

        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventType)
                .data(payload);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Send a heartbeat to all connected clients to keep connections alive.
     * Call this from a scheduled method every ~30 seconds.
     */
    public void heartbeat() {
        if (emitters.isEmpty()) return;

        SseEmitter.SseEventBuilder beat = SseEmitter.event()
                .name("heartbeat")
                .data(Map.of("ts", System.currentTimeMillis()));

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(beat);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    public int getClientCount() {
        return emitters.size();
    }
}
