package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.client.HttpAuthorityLockClient;
import com.skbingegalaxy.booking.client.HttpAuthorityLockClient.ResourceLockSummary;
import com.skbingegalaxy.booking.dto.CurrencyRateDto;
import com.skbingegalaxy.booking.service.CurrencyService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for FX rate / currency configuration. Currencies are a
 * platform-wide concept (not binge-scoped) so super-admin role is required.
 *
 * <h3>Authority Handover lock enforcement</h3>
 * Mutation endpoints check the Authority Handover resource-lock table when the
 * gateway has flagged the request as a delegated admin call (i.e. the
 * {@code X-Authority-Delegated} header is set to {@code true}). If the target
 * currency is locked by a native super-admin, the mutation is rejected with
 * <em>423 Locked</em> and a human-readable reason. Native super-admins (no
 * delegation header) are unaffected so they can always release their own
 * locks via the Authority Handover console.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin/currencies")
@RequiredArgsConstructor
@Slf4j
public class AdminCurrencyController {

    /** Resource-type token shared with frontend {@code LockBadge} / {@code useResourceLock}. */
    private static final String LOCK_TYPE = "CURRENCY";

    private final CurrencyService currencyService;
    private final HttpAuthorityLockClient lockClient;

    private boolean isSuperAdmin(String role) {
        return "SUPER_ADMIN".equalsIgnoreCase(role) || "ROLE_SUPER_ADMIN".equalsIgnoreCase(role);
    }

    private static boolean isDelegated(String header) {
        return "true".equalsIgnoreCase(header);
    }

    /**
     * @return {@code null} when the call should proceed, otherwise a 423 Locked
     *         payload that the caller must return to the client. Native
     *         super-admins (no X-Authority-Delegated header) always proceed —
     *         they need a path to release their own locks.
     */
    private ApiResponse<Object> lockedReason(String delegated, String code) {
        if (!isDelegated(delegated) || code == null || code.isBlank()) return null;
        ResourceLockSummary lock = lockClient.lookup(LOCK_TYPE, code);
        if (lock == null) return null;
        log.info("authority.lock.block resource={}#{} delegatedAdminBlocked=true reason={}",
            LOCK_TYPE, code, lock.getReason());
        String msg = "Currency " + code + " is locked by "
            + (lock.getLockedByName() != null ? lock.getLockedByName() : "super-admin")
            + ". Reason: " + lock.getReason()
            + ". Ask the lock owner to release it via the Authority Handover console.";
        return ApiResponse.error(msg);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CurrencyRateDto>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(currencyService.listAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CurrencyRateDto>> upsert(
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Authority-Delegated", required = false) String delegated,
            @Valid @RequestBody CurrencyRateDto request) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage currencies"));
        }
        ApiResponse<Object> locked = lockedReason(delegated, request.getCode());
        if (locked != null) {
            return ResponseEntity.status(423).body(ApiResponse.error(locked.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.ok(currencyService.upsert(request)));
    }

    @PostMapping("/{code}/toggle")
    public ResponseEntity<ApiResponse<CurrencyRateDto>> toggle(
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Authority-Delegated", required = false) String delegated,
            @PathVariable String code) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage currencies"));
        }
        ApiResponse<Object> locked = lockedReason(delegated, code);
        if (locked != null) {
            return ResponseEntity.status(423).body(ApiResponse.error(locked.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.ok(currencyService.toggleActive(code)));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Authority-Delegated", required = false) String delegated,
            @PathVariable String code) {
        if (!isSuperAdmin(role)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Only super admins can manage currencies"));
        }
        ApiResponse<Object> locked = lockedReason(delegated, code);
        if (locked != null) {
            return ResponseEntity.status(423).body(ApiResponse.error(locked.getMessage()));
        }
        currencyService.delete(code);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
