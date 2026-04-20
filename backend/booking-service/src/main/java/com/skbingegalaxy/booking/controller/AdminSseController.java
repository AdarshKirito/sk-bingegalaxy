package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.config.AdminEventBus;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for real-time admin updates, scoped to the admin's managed binge.
 *
 * <p>Uses the same {@link AdminBingeScopeService#requireManagedBinge} check as
 * {@link AdminBookingController} so only the binge owner (or SUPER_ADMIN) receives events.</p>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/events")
@RequiredArgsConstructor
@Slf4j
public class AdminSseController {

    private final AdminEventBus eventBus;
    private final AdminBingeScopeService adminBingeScopeService;

    /**
     * Validate that the requesting admin actually manages the selected binge
     * before allowing an SSE subscription. Without this, any authenticated admin
     * could spy on another venue's real-time events.
     */
    @ModelAttribute
    void validateManagedBinge(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role,
            HttpServletRequest request) {
        adminBingeScopeService.requireManagedBinge(adminId, role, "subscribing to live events");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {
        // requireManagedBinge already ran in @ModelAttribute, but we need the Binge entity
        var binge = adminBingeScopeService.requireManagedBinge(adminId, role, "subscribing to live events");
        log.info("Admin {} subscribed to SSE for binge {} ({})", adminId, binge.getId(), binge.getName());
        return eventBus.subscribe(binge.getId());
    }
}
