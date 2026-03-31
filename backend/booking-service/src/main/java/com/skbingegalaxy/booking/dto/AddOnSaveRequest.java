package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddOnSaveRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 300)
    private String description;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal price;

    @NotBlank
    @Size(max = 50)
    private String category;

    private List<String> imageUrls;
}
