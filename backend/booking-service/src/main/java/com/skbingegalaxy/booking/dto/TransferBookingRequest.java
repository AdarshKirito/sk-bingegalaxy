package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferBookingRequest {

    @NotBlank(message = "Recipient name is required")
    @Size(max = 150)
    private String recipientName;

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid email format")
    @Size(max = 150)
    private String recipientEmail;

    @Size(max = 15)
    private String recipientPhone;
}
