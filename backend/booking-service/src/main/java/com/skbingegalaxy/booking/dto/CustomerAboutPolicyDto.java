package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAboutPolicyDto {

    @Size(max = 90)
    private String title;

    @Size(max = 320)
    private String description;
}