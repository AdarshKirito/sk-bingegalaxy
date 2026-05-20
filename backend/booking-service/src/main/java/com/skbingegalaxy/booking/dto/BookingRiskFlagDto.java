package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.entity.BookingRiskFlag;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Read-side projection of {@link BookingRiskFlag} for the operator UI.
 * Exposes everything except the FK columns (we always render via
 * {@code bookingRef} so the dashboard can deep-link to a booking detail).
 */
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class BookingRiskFlagDto {
    private Long id;
    private String bookingRef;
    private Long bingeId;
    private Long customerId;
    private String ruleCode;
    private String severity;
    private String source;
    private String reason;
    private String evidence;
    private Long createdByAdminId;
    private boolean acknowledged;
    private Long acknowledgedByAdminId;
    private LocalDateTime acknowledgedAt;
    private String acknowledgedNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BookingRiskFlagDto from(BookingRiskFlag f) {
        return BookingRiskFlagDto.builder()
            .id(f.getId())
            .bookingRef(f.getBookingRef())
            .bingeId(f.getBingeId())
            .customerId(f.getCustomerId())
            .ruleCode(f.getRuleCode().name())
            .severity(f.getSeverity().name())
            .source(f.getSource().name())
            .reason(f.getReason())
            .evidence(f.getEvidence())
            .createdByAdminId(f.getCreatedByAdminId())
            .acknowledged(f.isAcknowledged())
            .acknowledgedByAdminId(f.getAcknowledgedByAdminId())
            .acknowledgedAt(f.getAcknowledgedAt())
            .acknowledgedNote(f.getAcknowledgedNote())
            .createdAt(f.getCreatedAt())
            .updatedAt(f.getUpdatedAt())
            .build();
    }
}
