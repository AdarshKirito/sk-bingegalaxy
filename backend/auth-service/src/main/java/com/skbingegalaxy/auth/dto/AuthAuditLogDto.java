package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.auth.entity.AuthAuditLog;

import java.time.LocalDateTime;

public record AuthAuditLogDto(
    Long id,
    String eventType,
    Long actorId,
    String actorRole,
    Long targetId,
    String targetEmail,
    String ipAddress,
    String userAgent,
    boolean success,
    String failureReason,
    String details,
    LocalDateTime createdAt
) {
    public static AuthAuditLogDto from(AuthAuditLog a) {
        return new AuthAuditLogDto(
            a.getId(),
            a.getEventType(),
            a.getActorId(),
            a.getActorRole(),
            a.getTargetId(),
            a.getTargetEmail(),
            a.getIpAddress(),
            a.getUserAgent(),
            a.isSuccess(),
            a.getFailureReason(),
            a.getDetails(),
            a.getCreatedAt()
        );
    }
}
