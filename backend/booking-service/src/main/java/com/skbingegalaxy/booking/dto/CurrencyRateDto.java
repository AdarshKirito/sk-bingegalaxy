package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrencyRateDto {
    private String code;
    private String name;
    private String symbol;
    private BigDecimal rateToBase;
    private Integer decimalDigits;
    private boolean active;
    private boolean base;
    private boolean manualOverride;
    private String lastUpdated;
    private boolean supportsDisplay;
    private boolean supportsPayment;
    private boolean supportsSettlement;
    private String fxSource;
}
