package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BingeSaveRequest {

    @NotBlank(message = "Binge name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 500)
    private String address;

    @Size(max = 150)
    private String supportEmail;

    @Size(max = 20)
    @Pattern(regexp = "^$|^[+0-9][0-9\\s-]{6,19}$", message = "Support phone must be a valid phone number")
    private String supportPhone;

    @Size(max = 20)
    @Pattern(regexp = "^$|^[0-9]{7,20}$", message = "WhatsApp number must contain digits only")
    private String supportWhatsapp;

    private Boolean customerCancellationEnabled;

    @PositiveOrZero(message = "Cancellation cutoff must be zero or more minutes")
    private Integer customerCancellationCutoffMinutes;

    @jakarta.validation.constraints.Min(value = 1, message = "Max concurrent bookings must be at least 1")
    private Integer maxConcurrentBookings;
}
