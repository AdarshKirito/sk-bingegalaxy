package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CashPaymentRequestedEvent extends EventEnvelope {
    private String bookingRef;
    private Long customerId;
    private BigDecimal amount;
    private String notes;
}
