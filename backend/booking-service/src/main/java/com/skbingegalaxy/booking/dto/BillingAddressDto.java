package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.entity.BillingAddress;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingAddressDto {
    private Long id;
    private Long customerId;
    private String fullName;
    private String email;
    private String phone;
    private String companyName;
    private String taxId;
    private String customerType; // B2C / B2B
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateCode;
    private String postalCode;
    private String countryCode;

    /** Map this DTO to a new entity (id is preserved when present so updates work). */
    public BillingAddress toEntity() {
        return BillingAddress.builder()
            .id(id)
            .customerId(customerId)
            .fullName(fullName)
            .email(email)
            .phone(phone)
            .companyName(companyName)
            .taxId(taxId)
            .customerType(customerType != null ? customerType : "B2C")
            .addressLine1(addressLine1)
            .addressLine2(addressLine2)
            .city(city)
            .stateCode(stateCode)
            .postalCode(postalCode)
            .countryCode(countryCode != null ? countryCode.toUpperCase() : null)
            .build();
    }

    public static BillingAddressDto fromEntity(BillingAddress a) {
        if (a == null) return null;
        return BillingAddressDto.builder()
            .id(a.getId())
            .customerId(a.getCustomerId())
            .fullName(a.getFullName())
            .email(a.getEmail())
            .phone(a.getPhone())
            .companyName(a.getCompanyName())
            .taxId(a.getTaxId())
            .customerType(a.getCustomerType())
            .addressLine1(a.getAddressLine1())
            .addressLine2(a.getAddressLine2())
            .city(a.getCity())
            .stateCode(a.getStateCode())
            .postalCode(a.getPostalCode())
            .countryCode(a.getCountryCode())
            .build();
    }
}
