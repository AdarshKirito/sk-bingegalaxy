package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.notification.dto.NotificationPreferenceDto;
import com.skbingegalaxy.notification.model.NotificationPreference;
import com.skbingegalaxy.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepo;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** Booking-critical / security types that always bypass quiet hours and cadence rules. */
    private static final Set<String> TRANSACTIONAL_TYPES = Set.of(
            "PASSWORD_RESET",
            "BOOKING_CONFIRMED",
            "BOOKING_CANCELLED",
            "PAYMENT_FAILED",
            "REFUND_PROCESSED"
    );

    private static final Set<String> MARKETING_TYPES = Set.of(
            "MARKETING",
            "REVIEW_REQUEST"
    );

    public NotificationPreferenceDto getPreferences(String email) {
        NotificationPreference pref = preferenceRepo.findByRecipientEmail(email)
                .orElseGet(() -> defaultPreference(email));
        return toDto(pref);
    }

    public NotificationPreferenceDto updatePreferences(String email, NotificationPreferenceDto dto) {
        NotificationPreference pref = preferenceRepo.findByRecipientEmail(email)
                .orElseGet(() -> defaultPreference(email));

        if (dto.getMutedTypes() != null) pref.setMutedTypes(dto.getMutedTypes());
        if (dto.getMutedChannels() != null) pref.setMutedChannels(dto.getMutedChannels());
        pref.setGlobalOptOut(dto.isGlobalOptOut());

        // Quiet hours
        pref.setQuietHoursEnabled(dto.isQuietHoursEnabled());
        pref.setQuietHoursStart(normalizeHHmm(dto.getQuietHoursStart()));
        pref.setQuietHoursEnd(normalizeHHmm(dto.getQuietHoursEnd()));
        pref.setQuietHoursTimezone(normalizeZone(dto.getQuietHoursTimezone()));

        // Cadence + routing
        if (dto.getMarketingFrequency() != null && !dto.getMarketingFrequency().isBlank()) {
            pref.setMarketingFrequency(dto.getMarketingFrequency().toUpperCase());
        }
        if (dto.getPrimaryChannel() != null && !dto.getPrimaryChannel().isBlank()) {
            pref.setPrimaryChannel(dto.getPrimaryChannel().toUpperCase());
        } else {
            pref.setPrimaryChannel(null);
        }

        pref.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        preferenceRepo.save(pref);
        log.info("Updated notification preferences for {}", email);
        return toDto(pref);
    }

    /**
     * Returns true if the given notification should be suppressed for this user.
     * Transactional / security-critical types are never suppressed regardless of
     * preferences (quiet hours, marketing cadence).
     */
    public boolean isSuppressed(String email, String type, String channel) {
        if (TRANSACTIONAL_TYPES.contains(type)) return false;

        return preferenceRepo.findByRecipientEmail(email).map(pref -> {
            if (pref.isGlobalOptOut()) return true;
            if (pref.getMutedTypes() != null && pref.getMutedTypes().contains(type)) return true;
            if (pref.getMutedChannels() != null && pref.getMutedChannels().contains(channel)) return true;

            // Marketing cadence — NEVER blocks all marketing types up-front.
            if (MARKETING_TYPES.contains(type)
                    && "NEVER".equalsIgnoreCase(pref.getMarketingFrequency())) {
                return true;
            }

            // Quiet hours — applies to non-transactional types (already filtered above).
            if (pref.isQuietHoursEnabled() && isInsideQuietHours(pref)) {
                log.debug("Suppressed by quiet hours: type={} channel={} email={}", type, channel, email);
                return true;
            }
            return false;
        }).orElse(false);
    }

    private boolean isInsideQuietHours(NotificationPreference pref) {
        try {
            String startRaw = pref.getQuietHoursStart();
            String endRaw = pref.getQuietHoursEnd();
            if (startRaw == null || endRaw == null) return false;

            ZoneId zone = pref.getQuietHoursTimezone() != null
                    ? ZoneId.of(pref.getQuietHoursTimezone())
                    : ZoneId.of("UTC");

            LocalTime now   = ZonedDateTime.now(zone).toLocalTime();
            LocalTime start = LocalTime.parse(startRaw, HHMM);
            LocalTime end   = LocalTime.parse(endRaw, HHMM);

            if (start.equals(end)) return false; // empty window
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            // Wraps past midnight (e.g. 22:00 → 07:00)
            return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) {
            log.warn("Quiet-hours check failed for {} ({}): {}",
                    pref.getRecipientEmail(), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private static String normalizeHHmm(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalTime.parse(s, HHMM).format(HHMM);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeZone(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZoneId.of(s).getId();
        } catch (Exception e) {
            return null;
        }
    }

    private NotificationPreference defaultPreference(String email) {
        return NotificationPreference.builder()
                .recipientEmail(email)
                .mutedTypes(new HashSet<>())
                .mutedChannels(new HashSet<>())
                .globalOptOut(false)
                .quietHoursEnabled(false)
                .marketingFrequency("IMMEDIATE")
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private NotificationPreferenceDto toDto(NotificationPreference p) {
        return NotificationPreferenceDto.builder()
                .recipientEmail(p.getRecipientEmail())
                .mutedTypes(p.getMutedTypes())
                .mutedChannels(p.getMutedChannels())
                .globalOptOut(p.isGlobalOptOut())
                .quietHoursEnabled(p.isQuietHoursEnabled())
                .quietHoursStart(p.getQuietHoursStart())
                .quietHoursEnd(p.getQuietHoursEnd())
                .quietHoursTimezone(p.getQuietHoursTimezone())
                .marketingFrequency(p.getMarketingFrequency() == null ? "IMMEDIATE" : p.getMarketingFrequency())
                .primaryChannel(p.getPrimaryChannel())
                .build();
    }
}
