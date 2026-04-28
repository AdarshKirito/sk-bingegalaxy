package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminCreateCustomerRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 2, max = 100, message = "First name must be between 2 and 100 characters")
    private String firstName;

    @Size(max = 100, message = "Last name must be at most 100 characters")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Pattern(regexp = "^$|^\\d{4,15}$", message = "Phone must be 4-15 digits without spaces or symbols")
    private String phone;

    @Pattern(regexp = "^$|^\\+\\d{1,4}$", message = "Phone country code must look like '+91' (1-4 digits)")
    private String phoneCountryCode;

    // ── Optional postal address ──
    @Size(max = 200) private String addressLine1;
    @Size(max = 200) private String addressLine2;
    @Size(max = 100) private String city;
    @Size(max = 100) private String state;
    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "Country must be an ISO-3166-1 alpha-2 code")
    private String country;
    @Pattern(regexp = "^$|^[A-Za-z0-9 \\-]{3,20}$", message = "Postal code must be 3-20 alphanumeric characters")
    private String postalCode;

    /** Optional — if omitted, a secure random password is generated server-side. */
    @Size(min = 10, max = 100, message = "Password must be between 10 and 100 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#.\\-_~+^]{10,}$",
        message = "Password must contain at least one uppercase, one lowercase, one digit and one special character"
    )
    private String password;
}
