package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxLockRequest {
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal baseAmount;
    private Integer ttlMinutes; // optional; default 15
}

