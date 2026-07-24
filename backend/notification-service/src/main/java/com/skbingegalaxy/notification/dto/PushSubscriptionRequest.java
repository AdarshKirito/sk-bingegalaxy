package com.skbingegalaxy.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body sent by the browser when subscribing to Web Push. Mirrors the shape of
 * {@code PushSubscription.toJSON()} — {@code endpoint} plus an object of client
 * {@code keys} ({@code p256dh}, {@code auth}). {@code userAgent} is added client-side
 * for diagnostics.
 */
@Data
public class PushSubscriptionRequest {

    @NotBlank
    private String endpoint;

    private Keys keys;

    private String userAgent;

    @Data
    public static class Keys {
        private String p256dh;
        private String auth;
    }
}
