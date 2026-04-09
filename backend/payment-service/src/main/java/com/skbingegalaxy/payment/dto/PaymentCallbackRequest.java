package com.skbingegalaxy.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentCallbackRequest {

    @NotBlank(message = "Gateway order ID is required")
    private String gatewayOrderId;

    private String gatewayPaymentId;

    private String gatewaySignature;

    @NotBlank(message = "Payment status is required")
    private String status;

    private String errorCode;

    private String errorDescription;
}
