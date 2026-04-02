package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BingeSaveRequest {

    @NotBlank(message = "Binge name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 500)
    private String address;
}
