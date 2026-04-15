package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.config.AdminEventBus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for real-time admin updates.
 * Admin clients connect to this stream and receive live booking/payment events.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/events")
@RequiredArgsConstructor
public class AdminSseController {

    private final AdminEventBus eventBus;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventBus.subscribe();
    }
}
