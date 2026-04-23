package com.skbingegalaxy.notification.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.notification.model.WhatsAppTemplate;
import com.skbingegalaxy.notification.repository.WhatsAppTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/notifications/admin/whatsapp-templates")
@RequiredArgsConstructor
@Slf4j
public class WhatsAppTemplateController {

    private final WhatsAppTemplateRepository repository;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<WhatsAppTemplate>>> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Cap page size to prevent memory exhaustion (template table can grow unbounded)
        if (size <= 0 || size > 100) size = 50;
        if (page < 0) page = 0;
        Page<WhatsAppTemplate> result = repository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "templateName")));
        return ResponseEntity.ok(ApiResponse.ok("WhatsApp templates", result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WhatsAppTemplate>> getById(@PathVariable String id) {
        return repository.findById(id)
                .map(t -> ResponseEntity.ok(ApiResponse.ok("WhatsApp template", t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WhatsAppTemplate>> create(@RequestBody WhatsAppTemplate template) {
        template.setId(null);
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        WhatsAppTemplate saved = repository.save(template);
        log.info("Created WhatsApp template: name={} contentSid={}", saved.getTemplateName(), saved.getContentSid());
        return ResponseEntity.ok(ApiResponse.ok("WhatsApp template created", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WhatsAppTemplate>> update(
            @PathVariable String id, @RequestBody WhatsAppTemplate template) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setTemplateName(template.getTemplateName());
                    existing.setContentSid(template.getContentSid());
                    existing.setDescription(template.getDescription());
                    existing.setActive(template.isActive());
                    existing.setUpdatedAt(LocalDateTime.now());
                    WhatsAppTemplate saved = repository.save(existing);
                    log.info("Updated WhatsApp template: id={} name={}", id, saved.getTemplateName());
                    return ResponseEntity.ok(ApiResponse.ok("WhatsApp template updated", saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        log.info("Deleted WhatsApp template: id={}", id);
        return ResponseEntity.ok(ApiResponse.ok("WhatsApp template deleted", null));
    }
}
