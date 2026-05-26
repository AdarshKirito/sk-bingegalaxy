package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategorySaveRequest {

    @NotBlank(message = "Category name is required")
    @Size(max = 80, message = "Category name must be under 80 characters")
    private String name;

    @Size(max = 500, message = "Description must be under 500 characters")
    private String description;

    @Size(max = 1000, message = "Image URL must be under 1000 characters")
    private String imageUrl;

    @Min(value = 0, message = "Sort order cannot be negative")
    private int sortOrder = 0;
}
