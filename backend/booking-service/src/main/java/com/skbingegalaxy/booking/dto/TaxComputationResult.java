package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of computing taxes for a booking. {@code totalTax} is the sum
 * of every line in {@code lines}. {@code breakdownJson} is the canonical
 * serialised form persisted on the booking row for invoices/audit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxComputationResult {

    private BigDecimal subtotal;       // taxable subtotal (post surge & loyalty)
    private BigDecimal totalTax;       // sum of exclusive taxes (added to subtotal)
    private BigDecimal totalInclusiveTax; // informational — already inside subtotal
    private List<TaxLine> lines;
    private String breakdownJson;
    /** Identifier of the tax engine that produced this result, e.g. "INTERNAL". */
    private String provider;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaxLine {
        private Long ruleId;
        private String name;
        private Integer rateBps;
        private boolean inclusive;
        private BigDecimal taxableAmount;
        private BigDecimal amount;
        /** GST / VAT / SALES_TAX label for invoice display. */
        private String taxType;
        /** "IN/MH/Mumbai/400001" or "GLOBAL". */
        private String jurisdiction;
        /** Human-readable computation formula for audit. */
        private String formula;
    }
}
