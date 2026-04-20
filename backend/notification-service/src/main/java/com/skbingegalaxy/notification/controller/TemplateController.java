package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.dto.NotificationTemplateDto;
import com.skbingegalaxy.notification.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications/admin/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationTemplateDto>>> listVersions(
            @RequestParam String name,
            @RequestParam String channel) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Template versions",
                templateService.listVersions(name, channel)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<NotificationTemplateDto>> createVersion(
            @RequestBody NotificationTemplateDto dto) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Template version created",
                templateService.createVersion(dto)));
    }

    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<NotificationTemplateDto>> activateVersion(
            @RequestParam String name,
            @RequestParam String channel,
            @RequestParam int version) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Template version activated",
                templateService.activateVersion(name, channel, version)));
    }
}
