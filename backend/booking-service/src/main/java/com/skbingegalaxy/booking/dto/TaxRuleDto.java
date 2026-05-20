package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.entity.TaxRule;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxRuleDto {
    private Long id;
    private Long bingeId;
    private String name;
    private String description;
    private Integer rateBps;
    private TaxRule.AppliesTo appliesTo;
    private boolean inclusive;
    private String countryCode;
    private String regionCode;
    private String stateCode;
    private String city;
    private String postalCode;
    private String productType;
    private String customerType;
    private String taxType;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Integer ruleVersion;
    private Integer priority;
    private boolean active;
}
