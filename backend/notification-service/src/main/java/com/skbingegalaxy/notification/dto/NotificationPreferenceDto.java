package com.skbingegalaxy.notification.dto;

import lombok.*;
import java.util.Set;

/**
 * Per-recipient notification preferences exposed to the SPA.
 *
 * <p>Backwards-compatible: older clients that send only
 * {@code mutedTypes / mutedChannels / globalOptOut} continue to work — the
 * new fields default to sensible no-op values on the server.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NotificationPreferenceDto {
    private String recipientEmail;
    private Set<String> mutedTypes;
    private Set<String> mutedChannels;
    private boolean globalOptOut;

    // Quiet hours
    private boolean quietHoursEnabled;
    private String quietHoursStart;     // "HH:mm" 24h local time
    private String quietHoursEnd;       // "HH:mm" 24h local time, may wrap past midnight
    private String quietHoursTimezone;  // IANA zone, e.g. "Asia/Kolkata"

    // Cadence + routing
    private String marketingFrequency;  // NEVER | WEEKLY | DAILY | IMMEDIATE
    private String primaryChannel;      // EMAIL | SMS | WHATSAPP | PUSH
}
