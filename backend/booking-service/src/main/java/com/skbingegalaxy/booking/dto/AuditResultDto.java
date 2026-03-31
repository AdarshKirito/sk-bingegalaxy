package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditResultDto {
    private LocalDate auditDate;
    private int totalProcessed;
    private int markedNoShow;
    private int markedCompleted;
    private List<String> affectedBookingRefs;
    /** Operational date after the audit completed (i.e. auditDate + 1 day). */
    private LocalDate newOperationalDate;
}
