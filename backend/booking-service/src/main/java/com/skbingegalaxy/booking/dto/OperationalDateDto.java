package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Returned by GET /api/bookings/admin/operational-date.
 * Tells the frontend what the current operational date is,
 * what time the server thinks it is, and whether an audit can be run.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationalDateDto {
    /** The current business day used by the system for "today". */
    private LocalDate operationalDate;
    /** Server date+time at the moment of the query. */
    private LocalDateTime serverDateTime;
    /** True if an audit can be triggered right now. */
    private boolean auditAvailable;
    /** Human-readable reason when auditAvailable == false. */
    private String auditUnavailableReason;
}
