package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.dto.AuthorityGrantDto;
import com.skbingegalaxy.auth.dto.CreateAuthorityGrantRequest;
import com.skbingegalaxy.auth.dto.CreateResourceLockRequest;
import com.skbingegalaxy.auth.dto.EffectiveAuthorityDto;
import com.skbingegalaxy.auth.dto.ResourceLockDto;
import com.skbingegalaxy.auth.service.AuthorityService;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.dto.PagedResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST surface for the Authority Handover system.
 *
 * <h3>Authorisation</h3>
 * Most endpoints here are restricted to {@code SUPER_ADMIN}. We rely on the gateway
 * to inject {@code X-User-Role} from the JWT — this is the same pattern used
 * everywhere else in this service. We additionally re-check role at the service
 * layer to defend against header spoofing should a downstream service ever be
 * exposed without the gateway in front.
 *
 * <p>{@code GET /me} is available to any authenticated user — it returns their own
 * effective authority (and is used by the frontend to render the right UI).</p>
 *
 * <h3>Status semantics</h3>
 * <ul>
 *   <li>200 — success (incl. idempotent re-hits)</li>
 *   <li>400 — invalid input (e.g., missing reason, invalid scope)</li>
 *   <li>403 — caller lacks SUPER_ADMIN</li>
 *   <li>404 — grant or lock not found</li>
 *   <li>409 — re-lock by a different owner ({@code BusinessException})</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth/authority")
@RequiredArgsConstructor
public class AuthorityController {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final AuthorityService authorityService;

    private static boolean isSuperAdmin(String role) {
        return ROLE_SUPER_ADMIN.equalsIgnoreCase(role)
            || ("ROLE_" + ROLE_SUPER_ADMIN).equalsIgnoreCase(role);
    }

    private static ResponseEntity<ApiResponse<Object>> forbidden(String msg) {
        return ResponseEntity.status(403).body(ApiResponse.error(msg));
    }

    // ──────────────────────────────────────────────────────────
    // Effective authority for the current user (any authenticated)
    // ──────────────────────────────────────────────────────────

    /**
     * Returns the caller's currently effective authority — own role plus any active
     * delegation grants. The frontend calls this on mount and after auth-state changes
     * so it can render the right UI. Cheap (one query + one scope union per call).
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<EffectiveAuthorityDto>> me(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(authorityService.getEffectiveAuthority(userId)));
    }

    // ──────────────────────────────────────────────────────────
    // GRANTS
    // ──────────────────────────────────────────────────────────

    @PostMapping("/grants")
    public ResponseEntity<ApiResponse<AuthorityGrantDto>> createGrant(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody CreateAuthorityGrantRequest body) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super-admins may issue authority grants"));
        }
        return ResponseEntity.ok(ApiResponse.ok(authorityService.createGrant(userId, body)));
    }

    @DeleteMapping("/grants/{id}")
    public ResponseEntity<ApiResponse<AuthorityGrantDto>> revokeGrant(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false) String reason) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super-admins may revoke authority grants"));
        }
        return ResponseEntity.ok(ApiResponse.ok(authorityService.revokeGrant(userId, id, reason)));
    }

    @GetMapping("/grants")
    public ResponseEntity<ApiResponse<PagedResponse<AuthorityGrantDto>>> listGrants(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).build();
        }
        Page<AuthorityGrantDto> result = activeOnly
            ? authorityService.listActiveGrants(PageRequest.of(page, size))
            : authorityService.listAllGrants(PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    @GetMapping("/grants/by-user/{userId}")
    public ResponseEntity<ApiResponse<List<AuthorityGrantDto>>> listGrantsForUser(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long userId) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(ApiResponse.ok(authorityService.listGrantsForUser(userId)));
    }

    // ──────────────────────────────────────────────────────────
    // LOCKS
    // ──────────────────────────────────────────────────────────

    @PostMapping("/locks")
    public ResponseEntity<ApiResponse<ResourceLockDto>> createLock(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody CreateResourceLockRequest body) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super-admins may place locks"));
        }
        return ResponseEntity.ok(ApiResponse.ok(authorityService.createLock(userId, body)));
    }

    @DeleteMapping("/locks/{id}")
    public ResponseEntity<ApiResponse<Void>> releaseLock(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false) String reason) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Only super-admins or the lock owner may release"));
        }
        authorityService.releaseLock(userId, id, reason);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Lookup a lock by (type, id). Authenticated callers (any role) may use this to
     * decide whether to render UI as read-only — the result includes the owner's
     * display name so the UI can show "Locked by Alice — Reason: …". No PII beyond
     * name is exposed.
     */
    @GetMapping("/locks/lookup")
    public ResponseEntity<ApiResponse<ResourceLockDto>> lookupLock(
            @RequestParam("type") String type,
            @RequestParam("id") String id) {
        Optional<ResourceLockDto> dto = authorityService.findLock(type, id);
        return dto.map(d -> ResponseEntity.ok(ApiResponse.ok(d)))
                  .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok(null)));
    }

    /**
     * Internal service-to-service lock lookup. Used by downstream mutation
     * endpoints (e.g. booking-service AdminCurrencyController) to reject writes
     * from delegated admins when the target resource has been locked by a
     * native super-admin. Path lives under {@code /internal/} so the
     * {@link com.skbingegalaxy.common.security.InternalApiAuthFilter} requires
     * the {@code X-Internal-Secret} header — preventing browsers from calling
     * it directly. Semantically identical to the public lookup endpoint.
     */
    @GetMapping("/internal/locks/lookup")
    public ResponseEntity<ApiResponse<ResourceLockDto>> internalLookupLock(
            @RequestParam("type") String type,
            @RequestParam("id") String id) {
        Optional<ResourceLockDto> dto = authorityService.findLock(type, id);
        return dto.map(d -> ResponseEntity.ok(ApiResponse.ok(d)))
                  .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok(null)));
    }

    @GetMapping("/locks")
    public ResponseEntity<ApiResponse<PagedResponse<ResourceLockDto>>> listLocks(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).build();
        }
        if (type != null && !type.isBlank()) {
            // Non-paged but bounded: per-type lists are small.
            List<ResourceLockDto> all = authorityService.listLocksByType(type);
            PagedResponse<ResourceLockDto> body = PagedResponse.<ResourceLockDto>builder()
                .content(all)
                .page(0).size(all.size()).totalElements(all.size()).totalPages(1)
                .last(true)
                .build();
            return ResponseEntity.ok(ApiResponse.ok(body));
        }
        Page<ResourceLockDto> result = authorityService.listAllLocks(PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(toPagedResponse(result)));
    }

    private static <T> PagedResponse<T> toPagedResponse(Page<T> p) {
        return PagedResponse.<T>builder()
            .content(p.getContent())
            .page(p.getNumber())
            .size(p.getSize())
            .totalElements(p.getTotalElements())
            .totalPages(p.getTotalPages())
            .last(p.isLast())
            .build();
    }
}
