package com.skbingegalaxy.booking.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.BingeRepository;
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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Freezes a REJECTED venue. A rejected binge must be operationally inert: its
 * owning admin may only fix its details, delete it, or re-request approval — never
 * configure or run it (create events, edit the dashboard/about page, set
 * cancellation tiers, loyalty, pricing, etc.). Before this gate existed, ownership
 * was the only check ({@code getManagedBinge}), so a rejected venue stayed fully
 * operable — a critical authorization hole.
 *
 * <p><b>Fail-closed by default.</b> For a rejected binge every admin WRITE is
 * blocked unless its path is on the small lifecycle allow-list. New operational
 * endpoints are therefore locked automatically without having to remember to guard
 * them — the safe direction for an authorization control.
 *
 * <p>Out of scope on purpose:
 * <ul>
 *   <li>SUPER_ADMIN — operates on any venue, including to repair a rejected one.</li>
 *   <li>Reads (GET/HEAD/OPTIONS) — harmless, and the SPA needs them to render the
 *       edit form and the rejection reason.</li>
 *   <li>PENDING/APPROVED venues — only REJECTED is frozen; a pending venue is still
 *       being set up, an approved one is live.</li>
 * </ul>
 * Complements {@link ModulePermissionInterceptor} (per-user module matrix) and the
 * AuthorityLockInterceptor (capability locks) — three orthogonal gates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BingeApprovalInterceptor implements HandlerInterceptor {

    private final ObjectProvider<BingeRepository> repoProvider;
    private final ObjectMapper objectMapper;

    /** {@code .../binges/{id}} or {@code .../binges/{id}/...} — the venue in the path. */
    private static final Pattern BINGE_IN_PATH = Pattern.compile("/binges/(\\d+)(?:/|$)");
    /** A bare {@code .../binges/{id}} with no trailing segment = the venue itself (edit/delete). */
    private static final Pattern BINGE_SELF = Pattern.compile("/binges/\\d+/?$");

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {
        String role = request.getHeader("X-User-Role");
        if (role == null || "SUPER_ADMIN".equalsIgnoreCase(role) || "CUSTOMER".equalsIgnoreCase(role)) {
            return true;
        }
        // Reads are allowed — the freeze is about operating a rejected venue, and the
        // SPA still needs to read it to show the edit form and the rejection reason.
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        String uri = request.getRequestURI();
        if (isLifecyclePath(uri, method)) {
            return true;
        }

        Long bingeId = bingeIdFromPath(uri);
        if (bingeId == null) {
            // Not identified by the path (e.g. event-type create) — fall back to the
            // selected-binge context, which is how those operations scope themselves.
            bingeId = BingeContext.getBingeId();
        }
        if (bingeId == null) {
            return true; // not a per-binge write; other gates decide
        }

        BingeRepository repo = repoProvider.getIfAvailable();
        if (repo == null) return true; // @WebMvcTest slice — no-op

        Optional<BingeApprovalStatus> status;
        try {
            status = repo.findStatusById(bingeId);
        } catch (Exception ex) {
            // A lookup failure must not brick every admin write (it would also be
            // breaking everything else). Log and fail OPEN on the transient error;
            // a confirmed REJECTED status below is what blocks.
            log.warn("Binge-approval check could not read status for binge {} ({} {}): {}",
                bingeId, method, uri, ex.getMessage());
            return true;
        }

        if (status.isPresent() && status.get() == BingeApprovalStatus.REJECTED) {
            log.info("Blocked write to REJECTED binge {} by role {} ({} {})", bingeId, role, method, uri);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                "This venue was rejected by a super-admin. Fix its details and re-request "
                + "approval before configuring or operating it.")));
            return false;
        }
        return true;
    }

    /**
     * Writes that MUST keep working on a rejected venue so its owner can recover it:
     * edit its details, delete it, re-request approval, or raise a country/timezone
     * change request. Also the change-request state machine itself (cancel, etc.).
     */
    private boolean isLifecyclePath(String uri, String method) {
        if (uri == null) return false;
        // Edit or delete the venue itself: PUT/DELETE .../binges/{id}
        if (("PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
                && BINGE_SELF.matcher(uri).find()) {
            return true;
        }
        return uri.endsWith("/re-request")
            || uri.endsWith("/country-request")
            || uri.endsWith("/timezone-request")
            || uri.contains("/change-requests");
    }

    private Long bingeIdFromPath(String uri) {
        if (uri == null) return null;
        Matcher m = BINGE_IN_PATH.matcher(uri);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
