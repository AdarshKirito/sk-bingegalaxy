package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDashboardSlideDto {

    @Size(max = 40)
    private String badge;

    @Size(max = 120)
    private String headline;

    @Size(max = 320)
    private String description;

    @Size(max = 40)
    private String ctaLabel;

    @Size(max = 500)
    private String imageUrl;

    @Pattern(regexp = "(?i)celebration|romance|cinema|team|family|luxury")
    private String theme;
}