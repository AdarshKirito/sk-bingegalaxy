package com.skbingegalaxy.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportContactDto {
    private String email;
    private String phoneDisplay;
    private String phoneRaw;
    private String whatsappRaw;
    private String hours;
}