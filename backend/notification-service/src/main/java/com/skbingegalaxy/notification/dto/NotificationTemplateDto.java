package com.skbingegalaxy.notification.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationTemplateDto {
    private String id;
    private String name;
    private String channel;
    private int version;
    private String content;
    private String subject;
    private boolean active;
}
