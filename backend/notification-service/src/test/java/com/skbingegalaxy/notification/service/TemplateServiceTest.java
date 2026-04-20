package com.skbingegalaxy.notification.service;

import com.skbingegalaxy.notification.dto.NotificationTemplateDto;
import com.skbingegalaxy.notification.model.NotificationTemplate;
import com.skbingegalaxy.notification.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock private NotificationTemplateRepository templateRepository;
    @InjectMocks private TemplateService templateService;

    @Test
    @DisplayName("createVersion auto-increments version number")
    void createVersion_autoIncrementsVersion() {
        NotificationTemplate existing = NotificationTemplate.builder()
                .name("BOOKING_CREATED").channel("EMAIL").version(2).build();
        when(templateRepository.findByNameAndChannelOrderByVersionDesc("BOOKING_CREATED", "EMAIL"))
                .thenReturn(List.of(existing));
        when(templateRepository.save(any(NotificationTemplate.class)))
                .thenAnswer(i -> {
                    NotificationTemplate t = i.getArgument(0);
                    t.setId("tpl-1");
                    return t;
                });

        NotificationTemplateDto result = templateService.createVersion(
                NotificationTemplateDto.builder()
                        .name("BOOKING_CREATED").channel("EMAIL")
                        .content("<h1>New</h1>").subject("New Subject")
                        .build());

        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    @DisplayName("createVersion starts at version 1 for new templates")
    void createVersion_startsAtVersion1_forNewTemplate() {
        when(templateRepository.findByNameAndChannelOrderByVersionDesc("NEW_TYPE", "SMS"))
                .thenReturn(List.of());
        when(templateRepository.save(any(NotificationTemplate.class)))
                .thenAnswer(i -> {
                    NotificationTemplate t = i.getArgument(0);
                    t.setId("tpl-2");
                    return t;
                });

        NotificationTemplateDto result = templateService.createVersion(
                NotificationTemplateDto.builder()
                        .name("NEW_TYPE").channel("SMS")
                        .content("Hello {{name}}").build());

        assertThat(result.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("activateVersion deactivates others and activates target")
    void activateVersion_deactivatesOthersAndActivatesTarget() {
        NotificationTemplate v1 = NotificationTemplate.builder()
                .id("t1").name("BOOKING_CREATED").channel("EMAIL").version(1)
                .active(true).build();
        NotificationTemplate v2 = NotificationTemplate.builder()
                .id("t2").name("BOOKING_CREATED").channel("EMAIL").version(2)
                .active(false).build();
        NotificationTemplate v3 = NotificationTemplate.builder()
                .id("t3").name("BOOKING_CREATED").channel("EMAIL").version(3)
                .active(false).build();

        when(templateRepository.findByNameAndChannelOrderByVersionDesc("BOOKING_CREATED", "EMAIL"))
                .thenReturn(List.of(v3, v2, v1));
        when(templateRepository.findByNameAndChannelAndVersion("BOOKING_CREATED", "EMAIL", 3))
                .thenReturn(Optional.of(v3));
        when(templateRepository.save(any(NotificationTemplate.class)))
                .thenAnswer(i -> i.getArgument(0));

        NotificationTemplateDto result = templateService.activateVersion("BOOKING_CREATED", "EMAIL", 3);

        assertThat(result.isActive()).isTrue();
        // v1 was active, should be deactivated via batch save
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationTemplate>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(templateRepository).saveAll(batchCaptor.capture());
        List<NotificationTemplate> deactivated = batchCaptor.getValue();
        assertThat(deactivated).hasSize(1);
        assertThat(deactivated.get(0).getId()).isEqualTo("t1");
        assertThat(deactivated.get(0).isActive()).isFalse();
    }

    @Test
    @DisplayName("activateVersion throws when version not found")
    void activateVersion_throwsWhenVersionNotFound() {
        when(templateRepository.findByNameAndChannelOrderByVersionDesc("X", "EMAIL"))
                .thenReturn(List.of());
        when(templateRepository.findByNameAndChannelAndVersion("X", "EMAIL", 99))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.activateVersion("X", "EMAIL", 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template not found");
    }

    @Test
    @DisplayName("getActiveTemplate returns active template")
    void getActiveTemplate_returnsActiveTemplate() {
        NotificationTemplate template = NotificationTemplate.builder()
                .id("t1").name("BOOKING_CREATED").channel("EMAIL")
                .version(1).active(true).content("<h1>Hi</h1>").build();

        when(templateRepository.findByNameAndChannelAndActiveTrue("BOOKING_CREATED", "EMAIL"))
                .thenReturn(Optional.of(template));

        Optional<NotificationTemplate> result = templateService.getActiveTemplate("BOOKING_CREATED", "EMAIL");

        assertThat(result).isPresent();
        assertThat(result.get().getContent()).isEqualTo("<h1>Hi</h1>");
    }
}
