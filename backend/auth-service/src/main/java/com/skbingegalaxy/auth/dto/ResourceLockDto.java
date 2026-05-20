package com.skbingegalaxy.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Read DTO for a {@code ResourceLock}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLockDto {
    private Long id;
    private String resourceType;
    private String resourceId;
    private Long lockedBy;
    private String lockedByName;
    private String lockedByEmail;
    private String reason;
    private LocalDateTime lockedAt;
}
