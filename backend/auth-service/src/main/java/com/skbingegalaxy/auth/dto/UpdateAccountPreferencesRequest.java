package com.skbingegalaxy.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class UpdateAccountPreferencesRequest {
    @Size(max = 100, message = "Preferred experience must be at most 100 characters")
    private String preferredExperience;

    @Size(max = 120, message = "Vibe preference must be at most 120 characters")
    private String vibePreference;

    @Min(value = 1, message = "Reminder lead time must be at least 1 day")
    @Max(value = 60, message = "Reminder lead time cannot exceed 60 days")
    private Integer reminderLeadDays;

    @Size(max = 20, message = "Birthday month must be at most 20 characters")
    private String birthdayMonth;

    @Size(max = 20, message = "Anniversary month must be at most 20 characters")
    private String anniversaryMonth;

    @Pattern(regexp = "WHATSAPP|EMAIL|CALLBACK", message = "Notification channel must be WHATSAPP, EMAIL, or CALLBACK")
    private String notificationChannel;

    private Boolean receivesOffers;
    private Boolean weekendAlerts;
    private Boolean conciergeSupport;
}