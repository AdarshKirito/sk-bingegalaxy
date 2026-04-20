package com.skbingegalaxy.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DeliveryEventDto {

    @NotBlank(message = "notificationId is required")
    private String notificationId;

    @NotBlank(message = "event is required")
    private String event;

    /** Optional provider-specific message ID for correlation. */
    private String providerMessageId;

    /** Optional ISO-8601 timestamp from the provider. */
    private String timestamp;
}
