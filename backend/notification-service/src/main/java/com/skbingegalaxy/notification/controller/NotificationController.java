package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<NotificationDto>>> getMyNotifications(
            @RequestHeader("X-User-Email") String email) {
        List<NotificationDto> notifications = notificationService.getByEmail(email);
        return ResponseEntity.ok(ApiResponse.ok("Your notifications", notifications));
    }

    @GetMapping("/booking/{bookingRef}")
    public ResponseEntity<ApiResponse<List<NotificationDto>>> getByBookingRef(
            @PathVariable String bookingRef) {
        List<NotificationDto> notifications = notificationService.getByBookingRef(bookingRef);
        return ResponseEntity.ok(ApiResponse.ok("Notifications for booking", notifications));
    }

    @PostMapping("/admin/retry-failed")
    public ResponseEntity<ApiResponse<Void>> retryFailed() {
        notificationService.retryFailedNotifications();
        return ResponseEntity.ok(ApiResponse.ok("Failed notifications retried", null));
    }
}
