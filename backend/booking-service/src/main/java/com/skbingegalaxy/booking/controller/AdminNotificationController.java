package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.AdminNotificationDto;
import com.skbingegalaxy.booking.service.AdminNotificationService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService service;

    /** Paginated notifications visible to the calling admin/super-admin. */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AdminNotificationDto>>> list(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            service.list(userId, role, PageRequest.of(page, Math.min(size, 50)))));
    }

    /** Bell-icon badge counter — count of unread notifications for the caller. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        long count = service.unreadCount(userId, role);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("unread", count)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> markRead(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(service.markRead(id, userId, role)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        int updated = service.markAllRead(userId, role);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("updated", updated)));
    }
}
