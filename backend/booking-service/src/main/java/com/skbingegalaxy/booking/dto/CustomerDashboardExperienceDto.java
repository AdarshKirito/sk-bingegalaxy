package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
public class CustomerDashboardExperienceDto {

    @Size(max = 60)
    private String sectionEyebrow;

    @Size(max = 140)
    private String sectionTitle;

    @Size(max = 240)
    private String sectionSubtitle;

    @Pattern(regexp = "(?i)GRID|CAROUSEL")
    private String layout;

    @Valid
    @Size(max = 6)
    @Builder.Default
    private List<CustomerDashboardSlideDto> slides = new ArrayList<>();
}