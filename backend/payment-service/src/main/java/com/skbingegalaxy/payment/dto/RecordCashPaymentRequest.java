package com.skbingegalaxy.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RecordCashPaymentRequest {

    @NotBlank(message = "Booking reference is required")
    private String bookingRef;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    private BigDecimal amount;

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    private String notes;
}
