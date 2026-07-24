package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.dto.PushSubscriptionRequest;
import com.skbingegalaxy.notification.service.WebPushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Browser Web Push subscription endpoints. All are behind the gateway JWT filter and the
 * service-level {@code .anyRequest().authenticated()} rule, so only a signed-in
 * customer/admin can register a subscription — and it is always bound to <em>their</em>
 * server-verified email (X-User-Email), never a value from the request body.
 */
@RestController
@RequestMapping("/api/v1/notifications/push")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final WebPushService webPushService;

    /** VAPID public key + whether push is actually deliverable. Fetched before subscribing. */
    @GetMapping("/public-key")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicKey() {
        return ResponseEntity.ok(ApiResponse.ok("VAPID public key", Map.of(
                "publicKey", webPushService.getPublicKey() == null ? "" : webPushService.getPublicKey(),
                "enabled", webPushService.isEnabled()
        )));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody PushSubscriptionRequest req) {
        webPushService.subscribe(email, parseUserId(userId), req);
        return ResponseEntity.ok(ApiResponse.ok("Subscribed to push notifications", null));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@RequestBody Map<String, String> body) {
        webPushService.unsubscribe(body == null ? null : body.get("endpoint"));
        return ResponseEntity.ok(ApiResponse.ok("Unsubscribed from push notifications", null));
    }

    /** Fire a test push to every device the caller has registered — lets a user confirm it works. */
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> test(@RequestHeader("X-User-Email") String email) {
        int devices = webPushService.fanout(
                email,
                "Test notification",
                "🎉 Push notifications are working on this device.",
                "/", "skbg-test", "TEST");
        return ResponseEntity.ok(ApiResponse.ok(
                devices > 0 ? "Test push sent to " + devices + " device(s)"
                            : "No active push subscriptions on this account",
                Map.of("devices", devices)));
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
