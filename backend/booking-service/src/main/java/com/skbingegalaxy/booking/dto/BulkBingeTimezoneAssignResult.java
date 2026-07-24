package com.skbingegalaxy.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Outcome of a {@link BulkBingeTimezoneAssignRequest} — best-effort across the batch. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkBingeTimezoneAssignResult {
    private int updatedCount;
    private List<Long> updatedBingeIds;
    /** Venue ids from the request that no longer exist (e.g. deleted mid-flight); skipped, not fatal. */
    private List<Long> notFoundBingeIds;
}
