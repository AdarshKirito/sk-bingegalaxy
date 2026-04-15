package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.notification.dto.NotificationPreferenceDto;
import com.skbingegalaxy.notification.model.NotificationPreference;
import com.skbingegalaxy.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepo;

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
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceRepo.save(pref);
        log.info("Updated notification preferences for {}", email);
        return toDto(pref);
    }

    /**
     * Returns true if the given notification should be suppressed for this user.
     * Transactional emails (PASSWORD_RESET) are never suppressed.
     */
    public boolean isSuppressed(String email, String type, String channel) {
        // Transactional / security-critical types are never suppressed
        if ("PASSWORD_RESET".equals(type)) return false;

        return preferenceRepo.findByRecipientEmail(email).map(pref -> {
            if (pref.isGlobalOptOut()) return true;
            if (pref.getMutedTypes() != null && pref.getMutedTypes().contains(type)) return true;
            return pref.getMutedChannels() != null && pref.getMutedChannels().contains(channel);
        }).orElse(false);
    }

    private NotificationPreference defaultPreference(String email) {
        return NotificationPreference.builder()
                .recipientEmail(email)
                .mutedTypes(new HashSet<>())
                .mutedChannels(new HashSet<>())
                .globalOptOut(false)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private NotificationPreferenceDto toDto(NotificationPreference p) {
        return NotificationPreferenceDto.builder()
                .recipientEmail(p.getRecipientEmail())
                .mutedTypes(p.getMutedTypes())
                .mutedChannels(p.getMutedChannels())
                .globalOptOut(p.isGlobalOptOut())
                .build();
    }
}
