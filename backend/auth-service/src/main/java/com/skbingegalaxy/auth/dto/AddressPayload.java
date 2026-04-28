package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Reusable address payload mixed into registration/profile/admin requests.
 * All fields are optional; when provided they must satisfy production-grade
 * validation:
 *   - country: ISO-3166-1 alpha-2 (e.g. "IN", "US")
 *   - postalCode: alphanumeric + spaces/dashes, max 20 chars
 *   - lines/city/state: trimmed, length-bounded
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddressPayload {

    @Size(max = 200, message = "Address line 1 must be at most 200 characters")
    private String addressLine1;

    @Size(max = 200, message = "Address line 2 must be at most 200 characters")
    private String addressLine2;

    @Size(max = 100, message = "City must be at most 100 characters")
    private String city;

    @Size(max = 100, message = "State/region must be at most 100 characters")
    private String state;

    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "Country must be an ISO-3166-1 alpha-2 code (e.g. IN, US)")
    private String country;

    @Pattern(regexp = "^$|^[A-Za-z0-9 \\-]{3,20}$", message = "Postal code must be 3-20 alphanumeric characters")
    private String postalCode;
}
