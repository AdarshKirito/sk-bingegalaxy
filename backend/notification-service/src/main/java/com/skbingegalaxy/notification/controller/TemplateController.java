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

    /**
     * List notification template versions.
     *
     * <p>Both filters are optional so the admin UI can render an inventory grid
     * on first load (no name selected yet) without throwing 400. Empty filters
     * return every version of every template, ordered newest-first; supplying
     * just {@code name} narrows to that template's channels; supplying both
     * returns the full version history for one (name, channel) pair.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationTemplateDto>>> listVersions(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String channel) {
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
