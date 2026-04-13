package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAboutExperienceDto {

    @Size(max = 60)
    private String sectionEyebrow;

    @Size(max = 140)
    private String sectionTitle;

    @Size(max = 240)
    private String sectionSubtitle;

    @Size(max = 140)
    private String heroTitle;

    @Size(max = 1200)
    private String heroDescription;

    @Size(max = 80)
    private String highlightsTitle;

    @Valid
    @Size(max = 8)
    @Builder.Default
    private List<CustomerAboutHighlightDto> highlights = new ArrayList<>();

    @Size(max = 80)
    private String houseRulesTitle;

    @Size(max = 12)
    @Builder.Default
    private List<@Size(max = 180) String> houseRules = new ArrayList<>();

    @Size(max = 80)
    private String policyTitle;

    @Valid
    @Size(max = 8)
    @Builder.Default
    private List<CustomerAboutPolicyDto> policies = new ArrayList<>();

    @Size(max = 80)
    private String contactHeading;

    @Size(max = 260)
    private String contactDescription;
}