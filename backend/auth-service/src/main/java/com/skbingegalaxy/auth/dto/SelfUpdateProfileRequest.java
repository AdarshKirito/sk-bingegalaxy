package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for the authenticated user updating their own non-credential profile data.
 *
 * <p>Email and password are intentionally excluded — those go through dedicated
 * endpoints that require additional verification (current password, OTP, etc.).
 * Role and active flags are admin-only and never accepted from this DTO.
 */
@Data
public class SelfUpdateProfileRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 50)
    @Pattern(regexp = "^[\\p{L} '.-]{1,50}$", message = "First name contains invalid characters")
    private String firstName;

    @Size(max = 50)
    @Pattern(regexp = "^$|^[\\p{L} '.-]{1,50}$", message = "Last name contains invalid characters")
    private String lastName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\d{4,15}$", message = "Phone must be 4-15 digits without spaces or symbols")
    private String phone;

    @NotBlank(message = "Phone country code is required")
    @Pattern(regexp = "^\\+\\d{1,4}$", message = "Phone country code must look like '+91' (1-4 digits)")
    private String phoneCountryCode;

    @Size(max = 200) private String addressLine1;
    @Size(max = 200) private String addressLine2;
    @Size(max = 100) private String city;
    @Size(max = 100) private String state;
    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "Country must be an ISO-3166-1 alpha-2 code")
    private String country;
    @Pattern(regexp = "^$|^[A-Za-z0-9 \\-]{3,20}$", message = "Postal code must be 3-20 alphanumeric characters")
    private String postalCode;
}
