package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.dto.NotificationDto;
import com.skbingegalaxy.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Validated
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
            @PathVariable String bookingRef,
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role) {
        List<NotificationDto> notifications;
        if ("ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role)) {
            notifications = notificationService.getByBookingRef(bookingRef);
        } else {
            notifications = notificationService.getByBookingRefAndEmail(bookingRef, email);
        }
        return ResponseEntity.ok(ApiResponse.ok("Notifications for booking", notifications));
    }

    @PostMapping("/admin/retry-failed")
    public ResponseEntity<ApiResponse<Void>> retryFailed(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role) {
        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only admins can retry failed notifications", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        notificationService.retryFailedNotifications();
        return ResponseEntity.ok(ApiResponse.ok("Failed notifications retried", null));
    }
}
