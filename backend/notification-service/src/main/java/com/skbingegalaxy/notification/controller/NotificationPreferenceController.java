package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.dto.NotificationPreferenceDto;
import com.skbingegalaxy.notification.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceDto>> getPreferences(
            @RequestHeader("X-User-Email") String email) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Notification preferences",
                preferenceService.getPreferences(email)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceDto>> updatePreferences(
            @RequestHeader("X-User-Email") String email,
            @RequestBody NotificationPreferenceDto dto) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Preferences updated",
                preferenceService.updatePreferences(email, dto)));
    }
}
