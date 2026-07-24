package com.skbingegalaxy.booking.permission;

import com.skbingegalaxy.booking.client.HttpAuthContactClient;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * About / Access / Permissions for a binge.
 *
 * <ul>
 *   <li><b>About</b> — binge identity + lifecycle audit (created/approved/by
 *       whom) + the current permission summary + remarks. Visible to the
 *       owning ADMIN (their own binge) and to SUPER_ADMIN (any binge).</li>
 *   <li><b>Access matrix</b> — explicit permission rows + audit trail.
 *       SUPER_ADMIN edits; the owning ADMIN can view (so the UI can explain
 *       WHY an option is missing) but never edit their own access.</li>
 *   <li><b>My permissions</b> — the caller's own denied modules for the
 *       selected binge; powers menu hiding in the SPA. Backend enforcement
 *       lives in {@link ModulePermissionInterceptor} regardless.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bookings/admin")
@RequiredArgsConstructor
public class BingeAccessController {

    private final BingeModulePermissionService permissionService;
    private final BingeRepository bingeRepository;
    private final AdminBingeScopeService adminBingeScopeService;
    private final HttpAuthContactClient authContactClient;

    // ── My permissions (menu hiding for the selected binge) ──────────────

    @GetMapping("/my-permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myPermissions(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        Long bingeId = BingeContext.getBingeId();
        Map<String, Object> body = new HashMap<>();
        body.put("modules", PermissionModules.ALL);
        body.put("deniedModules", "SUPER_ADMIN".equalsIgnoreCase(role) || bingeId == null
            ? List.of()
            : List.copyOf(permissionService.deniedModules(bingeId, userId)));
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── About ─────────────────────────────────────────────────────────────

    @GetMapping("/binges/{id}/about")
    public ResponseEntity<ApiResponse<Map<String, Object>>> about(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        Binge binge = adminBingeScopeService.requireBingeOwnership(id, userId, role, "viewing the About page");

        Map<String, Object> body = new HashMap<>();
        body.put("bingeId", binge.getId());
        body.put("name", binge.getName());
        body.put("status", binge.getStatus() != null ? binge.getStatus().name() : null);
        body.put("active", binge.isActive());
        body.put("createdAt", binge.getCreatedAt());
        body.put("updatedAt", binge.getUpdatedAt());
        body.put("createdByAdminId", binge.getAdminId());
        body.put("approvedByUserId", binge.getApprovalDecidedBy());
        body.put("approvedAt", binge.getApprovalDecidedAt());
        body.put("timezone", binge.getTimezone());
        body.put("currency", binge.getCurrency());
        body.put("country", binge.getCountry());
        body.put("city", binge.getCity());
        body.put("address", binge.getAddress());
        body.put("accessRemarks", binge.getAccessRemarks());

        // Best-effort owner identity (auth-service lookup; null on outage).
        HttpAuthContactClient.AdminContact owner = authContactClient.fetchAdminContact(binge.getAdminId());
        if (owner != null) {
            body.put("adminName", ((owner.getFirstName() != null ? owner.getFirstName() : "")
                + " " + (owner.getLastName() != null ? owner.getLastName() : "")).trim());
            body.put("adminEmail", owner.getEmail());
        }

        // Permission summary for the OWNING admin (module → state).
        java.util.List<Map<String, Object>> moduleStates = new java.util.ArrayList<>();
        Map<String, BingeModulePermission> rows = new HashMap<>();
        for (BingeModulePermission p : permissionService.matrix(binge.getId())) {
            if ("ALL".equals(p.getActionKey()) && p.getUserId().equals(binge.getAdminId())) {
                rows.put(p.getModuleKey(), p);
            }
        }
        for (String key : PermissionModules.ALL) {
            BingeModulePermission p = rows.get(key);
            Map<String, Object> m = new HashMap<>();
            m.put("module", key);
            m.put("state", p == null ? "ENABLED"
                : p.isLockedBySuperAdmin() ? "LOCKED"
                : p.isEnabled() ? "ENABLED" : "DISABLED");
            m.put("remarks", p != null ? p.getRemarks() : null);
            m.put("lastChangedBy", p != null ? p.getGrantedByUserId() : null);
            m.put("lastChangedAt", p != null ? p.getUpdatedAt() : null);
            moduleStates.add(m);
        }
        body.put("moduleStates", moduleStates);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── Access matrix ─────────────────────────────────────────────────────

    @GetMapping("/binges/{id}/access")
    public ResponseEntity<ApiResponse<Map<String, Object>>> accessMatrix(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        Binge binge = adminBingeScopeService.requireBingeOwnership(id, userId, role, "viewing access permissions");
        Map<String, Object> body = new HashMap<>();
        body.put("bingeId", binge.getId());
        body.put("ownerAdminId", binge.getAdminId());
        body.put("modules", PermissionModules.ALL);
        body.put("sensitiveModules", PermissionModules.SENSITIVE);
        body.put("rows", permissionService.matrix(binge.getId()));
        body.put("audit", permissionService.recentAudit(binge.getId()));
        body.put("accessRemarks", binge.getAccessRemarks());
        body.put("readOnly", !"SUPER_ADMIN".equalsIgnoreCase(role));
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** Body for {@link #setPermission}. */
    public record SetPermissionRequest(Long targetUserId, String targetRole,
                                       Boolean enabled, Boolean locked, String remarks) {}

    @PutMapping("/binges/{id}/access/{moduleKey}")
    public ResponseEntity<ApiResponse<BingeModulePermission>> setPermission(
            @PathVariable Long id,
            @PathVariable String moduleKey,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody SetPermissionRequest request) {
        // Today only SUPER_ADMIN manages the matrix (the target is the binge's
        // owning admin). The service additionally enforces the parent rules so
        // future ADMIN-managed sub-user rows inherit the same constraints.
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("Only a super-admin can change binge permissions", HttpStatus.FORBIDDEN);
        }
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        Long target = request.targetUserId() != null ? request.targetUserId() : binge.getAdminId();
        BingeModulePermission row = permissionService.setPermission(
            id, target,
            request.targetRole() != null ? request.targetRole() : "ADMIN",
            moduleKey,
            request.enabled() == null || request.enabled(),
            request.locked() != null && request.locked(),
            request.remarks(), userId, role);
        return ResponseEntity.ok(ApiResponse.ok("Permission updated", row));
    }

    /** Body for {@link #setAccessRemarks}. */
    public record RemarksRequest(String remarks) {}

    @PatchMapping("/binges/{id}/access-remarks")
    public ResponseEntity<ApiResponse<Void>> setAccessRemarks(
            @PathVariable Long id,
            @RequestHeader("X-User-Role") String role,
            @RequestBody RemarksRequest request) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("Only a super-admin can edit access remarks", HttpStatus.FORBIDDEN);
        }
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        String r = request.remarks() != null ? request.remarks().trim() : null;
        binge.setAccessRemarks(r == null || r.isEmpty() ? null
            : (r.length() > 1000 ? r.substring(0, 1000) : r));
        bingeRepository.save(binge);
        return ResponseEntity.ok(ApiResponse.ok("Remarks saved", null));
    }
}
