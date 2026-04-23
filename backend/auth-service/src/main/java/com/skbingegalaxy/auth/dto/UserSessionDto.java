package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.auth.entity.UserSession;

import java.time.LocalDateTime;

public record UserSessionDto(
    Long id,
    Long userId,
    String ipAddress,
    String userAgent,
    String deviceLabel,
    LocalDateTime createdAt,
    LocalDateTime lastSeenAt,
    LocalDateTime expiresAt,
    LocalDateTime revokedAt,
    boolean current
) {
    public static UserSessionDto from(UserSession s, boolean current) {
        return new UserSessionDto(
            s.getId(),
            s.getUserId(),
            s.getIpAddress(),
            s.getUserAgent(),
            s.getDeviceLabel(),
            s.getCreatedAt(),
            s.getLastSeenAt(),
            s.getExpiresAt(),
            s.getRevokedAt(),
            current
        );
    }
}
