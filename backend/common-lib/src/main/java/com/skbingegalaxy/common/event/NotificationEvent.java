package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skbingegalaxy.common.enums.NotificationChannel;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationEvent extends EventEnvelope {
    private String recipientEmail;
    private String recipientPhone;
    /** E.164 dial prefix without subscriber number (e.g. "+91"). */
    private String recipientPhoneCountryCode;
    private String recipientName;
    private NotificationChannel channel;
    private String templateName;
    private Map<String, String> templateData;
    private String type;
    private String subject;
    private String body;
    private String bookingRef;
    private Map<String, Object> metadata;
}
