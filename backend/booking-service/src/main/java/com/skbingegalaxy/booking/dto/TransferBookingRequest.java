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

    @Pattern(regexp = "^$|^\\d{4,15}$", message = "Phone must be 4-15 digits")
    @Size(max = 20)
    private String recipientPhone;

    /** E.164 dial prefix without subscriber number (e.g. "+91"). */
    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "Phone country code must be in '+<digits>' format")
    private String recipientPhoneCountryCode;
}
