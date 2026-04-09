package com.skbingegalaxy.common.event;

import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashPaymentRequestedEvent implements Serializable {
    private String bookingRef;
    private Long customerId;
    private BigDecimal amount;
    private String notes;
}
