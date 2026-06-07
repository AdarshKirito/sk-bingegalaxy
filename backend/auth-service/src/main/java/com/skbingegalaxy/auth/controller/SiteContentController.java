package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.entity.SiteContent;
import com.skbingegalaxy.auth.repository.SiteContentRepository;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight CMS endpoint for editable static pages (the public landing
 * page in particular). The schema is intentionally a single JSON blob so
 * super-admins can iterate on layout without us shipping new migrations
 * for every section. Mirrors how Notion / Webflow store page documents.
 */
@RestController
@RequestMapping("/api/v1/site-content")
@RequiredArgsConstructor
@Slf4j
public class SiteContentController {

    private final SiteContentRepository repo;

    /** Public read — used by the landing page render before auth. */
    @GetMapping("/public/{slug}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublic(@PathVariable String slug) {
        Map<String, Object> body = new HashMap<>();
        repo.findById(slug).ifPresent(sc -> {
            body.put("slug", sc.getSlug());
            body.put("contentJson", sc.getContentJson());
            body.put("updatedAt", sc.getUpdatedAt());
        });
        if (body.isEmpty()) {
            body.put("slug", slug);
            body.put("contentJson", null);
        }
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** Super-admin write. */
    @PutMapping("/admin/{slug}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsert(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = "X-User-Id", required = false) Long userId) {

        Object raw = body.get("contentJson");
        if (raw == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("contentJson is required"));
        }
        String json = raw instanceof String s ? s : raw.toString();
        if (json.length() > 2_000_000) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Content too large (max 2MB)"));
        }

        SiteContent sc = repo.findById(slug).orElseGet(() -> SiteContent.builder().slug(slug).build());
        sc.setContentJson(json);
        sc.setUpdatedBy(userId);
        sc.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        repo.save(sc);

        Map<String, Object> resp = new HashMap<>();
        resp.put("slug", sc.getSlug());
        resp.put("contentJson", sc.getContentJson());
        resp.put("updatedAt", sc.getUpdatedAt());
        log.info("site-content saved slug={} by user={}", slug, userId);
        return ResponseEntity.ok(ApiResponse.ok("Saved", resp));
    }
}
