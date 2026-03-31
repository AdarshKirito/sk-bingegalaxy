package com.skbingegalaxy.common.event;

import com.skbingegalaxy.common.enums.NotificationChannel;
import lombok.*;
import java.io.Serializable;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEvent implements Serializable {
    private String recipientEmail;
    private String recipientPhone;
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
