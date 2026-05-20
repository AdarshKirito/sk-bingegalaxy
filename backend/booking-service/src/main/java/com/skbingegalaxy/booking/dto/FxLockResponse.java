package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxLockResponse {
    private String lockToken;
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal fxRate;
    private String fxSource;
    private BigDecimal baseAmount;
    private BigDecimal convertedAmount;
    private LocalDateTime lockedAt;
    private LocalDateTime lockedUntil;
    private String status;
}
