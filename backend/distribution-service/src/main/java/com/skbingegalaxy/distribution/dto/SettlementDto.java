package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.SettlementRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One receivable as the console sees it.
 *
 * <p>Amounts stay in <b>minor units</b> all the way to the browser. Converting to a
 * decimal here would introduce a rounding step in the one place a rounding step is least
 * welcome — money a venue is owed — and the client already formats minor units for every
 * other amount in the product.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementDto {
    private Long id;
    private String bookingRef;
    private String destinationCode;
    private String settlementCurrency;
    private long grossMinor;
    private long commissionMinor;
    private long outstandingMinor;
    private SettlementRecord.CollectedBy collectedBy;
    private SettlementRecord.SettlementStatus settlementStatus;
    private SettlementRecord.ReconciliationStatus reconciliationStatus;
    private LocalDate expectedPayoutAt;
    private LocalDate actualPayoutAt;
    private Long actualPayoutMinor;

    /** Signed difference. Negative means the destination paid less than it owed. */
    private long varianceMinor;

    /** True once expectedPayoutAt has passed and the money still has not arrived. */
    private boolean overdue;
}
