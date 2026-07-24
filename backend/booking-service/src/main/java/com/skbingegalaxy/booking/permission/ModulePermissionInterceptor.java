package com.skbingegalaxy.booking.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Backend enforcement of the per-binge module permission matrix: when the
 * SUPER_ADMIN disabled/locked a module for an admin, EVERY request (reads
 * included) that admin makes to that module's endpoints returns
 * {@code 403 Forbidden} — hiding menu items in the SPA is presentation only,
 * per the access-control spec ("do not rely only on frontend hiding").
 *
 * <p>Sits alongside {@link com.skbingegalaxy.booking.config.AuthorityLockInterceptor}
 * (capability locks on mutations); this one gates whole modules per user.
 * SUPER_ADMIN and customer traffic pass untouched; unmapped admin paths (the
 * Binges list, dashboard stats, account endpoints) are never blocked.
 * Module-mapped paths fail CLOSED when the binge/user context is missing —
 * otherwise omitting the X-Binge-Id header would bypass a module lock.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModulePermissionInterceptor implements HandlerInterceptor {

    private final ObjectProvider<BingeModulePermissionService> serviceProvider;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {
        String role = request.getHeader("X-User-Role");
        // Only binge-level roles are subject to the matrix. SUPER_ADMIN owns it;
        // customers never reach these admin paths (Spring Security rejects first).
        if (role == null || "SUPER_ADMIN".equalsIgnoreCase(role) || "CUSTOMER".equalsIgnoreCase(role)) {
            return true;
        }
        String module = PermissionModules.resolveModule(request.getRequestURI());
        if (module == null) return true;

        BingeModulePermissionService service = serviceProvider.getIfAvailable();
        if (service == null) return true; // @WebMvcTest slice — no-op

        Long userId = parseLong(request.getHeader("X-User-Id"));
        Long bingeId = BingeContext.getBingeId();
        if (userId == null || bingeId == null) {
            // Fail closed: this path IS module-mapped and the matrix can only
            // be evaluated with both identities. Passing the request through
            // would let an admin bypass a SUPER_ADMIN module lock simply by
            // omitting the X-Binge-Id header.
            log.warn("Module '{}' request rejected — missing {} ({} {})",
                module, userId == null ? "user id" : "binge context",
                request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("Select a binge before using this option.")));
            return false;
        }

        if (service.isDenied(bingeId, userId, module)) {
            log.info("Module '{}' denied for user {} on binge {} ({} {})",
                module, userId, bingeId, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("This option is disabled by Super Admin.")));
            return false;
        }
        return true;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
