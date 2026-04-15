package com.skbingegalaxy.notification.dto;

import lombok.*;
import java.util.Set;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationPreferenceDto {
    private String recipientEmail;
    private Set<String> mutedTypes;
    private Set<String> mutedChannels;
    private boolean globalOptOut;
}
