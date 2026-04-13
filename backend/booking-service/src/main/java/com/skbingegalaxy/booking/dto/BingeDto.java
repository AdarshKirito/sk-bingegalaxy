package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeDto {
    private Long id;
    private String name;
    private String address;
    private Long adminId;
    private boolean active;
    private LocalDate operationalDate;
    private String supportEmail;
    private String supportPhone;
    private String supportWhatsapp;
    private boolean customerCancellationEnabled;
    private int customerCancellationCutoffMinutes;
    private LocalDateTime createdAt;
}
