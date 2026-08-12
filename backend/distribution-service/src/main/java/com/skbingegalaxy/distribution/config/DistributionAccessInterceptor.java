package com.skbingegalaxy.distribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.distribution.client.BingeAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * The tenancy boundary for every venue-facing distribution endpoint.
 *
 * <p><b>What was wrong.</b> {@code SecurityConfig} gated these paths on
 * {@code hasAnyRole("ADMIN", "SUPER_ADMIN")} and the services scoped their queries by the
 * {@code X-Binge-Id} header — with nothing checking that the caller had any relationship
 * to the venue in that header. The header comes from the venue picker in the browser, so
 * changing one number let any authenticated admin read another venue's connections, pause
 * or revoke its sales channels, and issue itself a reseller key against them. Every
 * service method was scrupulously "scoped by bingeId"; the id was simply not the caller's
 * to choose. The comments asserting the boundary was enforced described an intention.
 *
 * <p><b>Where the check belongs.</b> An interceptor rather than a line in each service:
 * this surface has 18 venue-facing endpoints across four controllers, and the failure mode
 * of the per-method approach is a new endpoint that forgets it — which is exactly how the
 * gap arose. One choke point cannot be forgotten by an endpoint that has not been written
 * yet.
 *
 * <p><b>Two questions, both required.</b> Ownership ("is this your venue?") and the V71
 * module grant ("has the super-admin allowed you DISTRIBUTION here?"). The second is not
 * decoration: connecting a provider publishes a venue's inventory to the open market and
 * issues credentials another company authenticates with, which is why {@code DISTRIBUTION}
 * is on the SENSITIVE list. The console hid the menu item; hiding is presentation, and the
 * API was answering anyone who typed the URL.
 *
 * <p><b>Fails closed, in all three directions.</b> Missing context, an unresolvable venue
 * and an unreachable booking-service are all refusals. An outage that quietly disabled the
 * ownership check would be indistinguishable from the bug this class exists to close.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributionAccessInterceptor implements HandlerInterceptor {

    /** The V71 module key for third-party distribution. */
    static final String MODULE = "DISTRIBUTION";

    private final BingeAccessClient bingeAccessClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        String uri = request.getRequestURI();
        if (!isVenueFacing(uri)) return true;

        String role = request.getHeader("X-User-Role");
        // The super-admin owns the module matrix and operates across venues; they are the
        // one caller for whom a venue they do not own is legitimate.
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) return true;

        Long userId = parseLong(request.getHeader("X-User-Id"));
        Long bingeId = parseLong(request.getHeader("X-Binge-Id"));
        if (userId == null || bingeId == null) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                "Select a venue before managing distribution.",
                "missing {} on {} {}", userId == null ? "user id" : "venue",
                request.getMethod(), uri);
        }

        BingeAccessClient.Lookup lookup = bingeAccessClient.lookup(bingeId, userId);

        if (lookup instanceof BingeAccessClient.Lookup.Unavailable unavailable) {
            // 503, not 403: the caller may well be entitled, and telling them they are
            // not would send them looking for a permissions problem that does not exist.
            return deny(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Unable to validate venue ownership right now. Try again shortly.",
                "ownership lookup unavailable ({}) for venue {} on {} {}",
                unavailable.reason(), bingeId, request.getMethod(), uri);
        }
        if (!(lookup instanceof BingeAccessClient.Lookup.Found found)) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                "Access denied: you do not manage this venue.",
                "unknown venue {} requested by user {} on {} {}",
                bingeId, userId, request.getMethod(), uri);
        }

        if (!Objects.equals(found.adminId(), userId)) {
            // Logged at WARN with both ids: a mismatch here is someone addressing a venue
            // that is not theirs, which is worth being able to find afterwards.
            log.warn("Distribution access DENIED: user {} is not the admin of venue {} ({} {})",
                userId, bingeId, request.getMethod(), uri);
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                "Access denied: you do not manage this venue.", null);
        }

        if (found.deniedModules() != null && found.deniedModules().contains(MODULE)) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                "This option is disabled by Super Admin.",
                "module {} denied for user {} on venue {} ({} {})",
                MODULE, userId, bingeId, request.getMethod(), uri);
        }

        return true;
    }

    /**
     * Venue-facing means "acts on the selected venue on behalf of a logged-in admin".
     *
     * <p>Two prefixes are deliberately excluded because they are not that, and applying an
     * admin ownership check to them would break them:
     * <ul>
     *   <li>{@code /octo/**} — another company's system authenticating with a per-reseller
     *       key that resolves to a connection. There is no admin, no session and no
     *       {@code X-Binge-Id}; the venue comes from the key itself.</li>
     *   <li>{@code /internal/**} — service-to-service, already gated on {@code ROLE_SYSTEM}
     *       by the shared secret and 404'd from the internet by the gateway.</li>
     * </ul>
     *
     * <p>Everything else under {@code /api/v1/distribution} is included by default, so a
     * new endpoint is covered the moment it is written rather than when someone remembers
     * to add it.
     */
    static boolean isVenueFacing(String uri) {
        if (uri == null || !uri.startsWith("/api/v1/distribution")) return false;
        return !uri.startsWith("/api/v1/distribution/octo")
            && !uri.startsWith("/api/v1/distribution/internal");
    }

    private boolean deny(HttpServletResponse response, int status, String message,
                         String logFormat, Object... logArgs) throws Exception {
        if (logFormat != null) {
            log.info("Distribution access denied — " + logFormat, logArgs);
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message)));
        return false;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return null; }
    }
}
