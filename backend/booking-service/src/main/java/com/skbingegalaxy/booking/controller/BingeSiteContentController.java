package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.BingeSiteContent;
import com.skbingegalaxy.booking.repository.BingeSiteContentRepository;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-binge CMS endpoints. The admin who owns a binge can override the
 * platform-wide account-page CMS document with venue-specific FAQ,
 * member offers, support hours, and policy text.
 *
 * <p>Reads are authenticated (any logged-in user) so customers viewing
 * their dashboard can fetch the override. Writes are gated by the gateway
 * to ADMIN/SUPER_ADMIN and additionally enforce binge ownership here so a
 * regular admin cannot write content for a binge they do not own.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
public class BingeSiteContentController {

    /** Hard cap on stored JSON payload (mirrors the global site_content controller). */
    private static final int MAX_CONTENT_BYTES = 2_000_000;

    /** Slug allow-list — defensive: only known CMS slots can be written. */
    private static final java.util.Set<String> ALLOWED_SLUGS = java.util.Set.of("account-page");

    private final BingeSiteContentRepository repo;
    private final AdminBingeScopeService adminBingeScopeService;

    /** Authenticated read — used by the customer Dashboard / Account Center. */
    @GetMapping("/binges/{bingeId}/site-content/{slug}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBingeSiteContent(
            @PathVariable Long bingeId,
            @PathVariable String slug) {
        Map<String, Object> body = new HashMap<>();
        repo.findByBingeIdAndSlug(bingeId, slug).ifPresent(sc -> {
            body.put("bingeId", sc.getBingeId());
            body.put("slug", sc.getSlug());
            body.put("contentJson", sc.getContentJson());
            body.put("updatedAt", sc.getUpdatedAt());
        });
        if (body.isEmpty()) {
            body.put("bingeId", bingeId);
            body.put("slug", slug);
            body.put("contentJson", null);
        }
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** Admin write — gated by ownership of the binge. */
    @PutMapping("/admin/binges/{bingeId}/site-content/{slug}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertBingeSiteContent(
            @PathVariable Long bingeId,
            @PathVariable String slug,
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader("X-User-Id") Long adminId,
            @RequestHeader("X-User-Role") String role) {

        if (!ALLOWED_SLUGS.contains(slug)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Unknown slug: " + slug));
        }

        Object raw = requestBody.get("contentJson");
        if (raw == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("contentJson is required"));
        }
        String json = raw instanceof String s ? s : raw.toString();
        if (json.length() > MAX_CONTENT_BYTES) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Content too large (max 2MB)"));
        }

        // Throws 403 if the calling admin does not own the binge (super-admin bypasses).
        adminBingeScopeService.requireBingeOwnership(bingeId, adminId, role, "editing binge site content");

        BingeSiteContent sc = repo.findByBingeIdAndSlug(bingeId, slug)
            .orElseGet(() -> BingeSiteContent.builder().bingeId(bingeId).slug(slug).build());
        sc.setContentJson(json);
        sc.setUpdatedBy(adminId);
        sc.setUpdatedAt(LocalDateTime.now());
        repo.save(sc);

        Map<String, Object> resp = new HashMap<>();
        resp.put("bingeId", sc.getBingeId());
        resp.put("slug", sc.getSlug());
        resp.put("contentJson", sc.getContentJson());
        resp.put("updatedAt", sc.getUpdatedAt());
        log.info("binge-site-content saved binge={} slug={} by user={}", bingeId, slug, adminId);
        return ResponseEntity.ok(ApiResponse.ok("Saved", resp));
    }
}
