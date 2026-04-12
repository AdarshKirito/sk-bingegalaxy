package com.skbingegalaxy.payment.dto;

import com.skbingegalaxy.common.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AddPaymentRequest {

    @NotBlank(message = "Booking reference is required")
    private String bookingRef;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    private BigDecimal amount;

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;

    /** Optional: the booking's totalAmount. When provided, the backend rejects payments
     *  that would push cumulative collected above this ceiling. */
    private BigDecimal bookingTotalAmount;

    private String notes;
}
