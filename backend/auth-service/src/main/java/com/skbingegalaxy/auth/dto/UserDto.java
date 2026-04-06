package com.skbingegalaxy.auth.dto;

import com.skbingegalaxy.common.enums.UserRole;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String preferredExperience;
    private String vibePreference;
    private Integer reminderLeadDays;
    private String birthdayMonth;
    private String anniversaryMonth;
    private String notificationChannel;
    private boolean receivesOffers;
    private boolean weekendAlerts;
    private boolean conciergeSupport;
    private UserRole role;
    private boolean active;
    private LocalDateTime createdAt;
}
