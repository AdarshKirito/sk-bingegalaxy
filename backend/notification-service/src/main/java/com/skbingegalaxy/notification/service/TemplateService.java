package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.notification.dto.NotificationTemplateDto;
import com.skbingegalaxy.notification.model.NotificationTemplate;
import com.skbingegalaxy.notification.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final NotificationTemplateRepository templateRepository;

    /**
     * Get the active template for a given name and channel.
     */
    public Optional<NotificationTemplate> getActiveTemplate(String name, String channel) {
        return templateRepository.findByNameAndChannelAndActiveTrue(name, channel);
    }

    /**
     * List all versions of a template.
     */
    public List<NotificationTemplateDto> listVersions(String name, String channel) {
        return templateRepository.findByNameAndChannelOrderByVersionDesc(name, channel)
                .stream().map(this::toDto).toList();
    }

    /**
     * Create a new version of a template. Auto-increments version number.
     */
    public NotificationTemplateDto createVersion(NotificationTemplateDto dto) {
        // Determine next version
        List<NotificationTemplate> existing = templateRepository
                .findByNameAndChannelOrderByVersionDesc(dto.getName(), dto.getChannel());
        int nextVersion = existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1;

        NotificationTemplate template = NotificationTemplate.builder()
                .name(dto.getName())
                .channel(dto.getChannel())
                .version(nextVersion)
                .content(dto.getContent())
                .subject(dto.getSubject())
                .active(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        template = templateRepository.save(template);
        log.info("Created template version: name={} channel={} version={}", dto.getName(), dto.getChannel(), nextVersion);
        return toDto(template);
    }

    /**
     * Activate a specific version, deactivating all others for the same name+channel.
     */
    public NotificationTemplateDto activateVersion(String name, String channel, int version) {
        // Deactivate all currently-active versions in one batch
        List<NotificationTemplate> allVersions = templateRepository
                .findByNameAndChannelOrderByVersionDesc(name, channel);
        List<NotificationTemplate> toDeactivate = allVersions.stream()
                .filter(t -> t.isActive() && t.getVersion() != version)
                .toList();
        if (!toDeactivate.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            toDeactivate.forEach(t -> {
                t.setActive(false);
                t.setUpdatedAt(now);
            });
            templateRepository.saveAll(toDeactivate);
        }

        // Activate the requested version
        NotificationTemplate target = templateRepository.findByNameAndChannelAndVersion(name, channel, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template not found: name=" + name + " channel=" + channel + " version=" + version));
        target.setActive(true);
        target.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(target);

        log.info("Activated template: name={} channel={} version={}", name, channel, version);
        return toDto(target);
    }

    private NotificationTemplateDto toDto(NotificationTemplate t) {
        return NotificationTemplateDto.builder()
                .id(t.getId())
                .name(t.getName())
                .channel(t.getChannel())
                .version(t.getVersion())
                .content(t.getContent())
                .subject(t.getSubject())
                .active(t.isActive())
                .build();
    }
}
