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
public class CustomerAboutHighlightDto {

    @Size(max = 80)
    private String title;

    @Size(max = 260)
    private String description;
}