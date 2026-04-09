package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class InitiatePaymentRequest {

    @NotBlank(message = "Booking reference is required")
    private String bookingRef;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    @DecimalMax(value = "1000000.00", message = "Amount must not exceed 1,000,000.00")
    private BigDecimal amount;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;

    private String currency;
}
