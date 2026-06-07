package com.skbingegalaxy.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.service.AuthorityLockGuard;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Authority Handover — central enforcement of super-admin <em>capability</em> locks on
 * binge-admin mutations across {@code /api/v1/bookings/admin/**}.
 *
 * <p>Pricing is enforced inline in {@link com.skbingegalaxy.booking.controller.AdminPricingController}
 * (it has a single scope choke point), so {@code /pricing/} is intentionally NOT mapped here.
 * Every other lockable capability lives across the large {@code AdminBookingController} /
 * {@code AdminTaxController}, which mix many capabilities under one controller — a path-mapped
 * interceptor is the clean way to enforce them without editing dozens of endpoints.
 *
 * <p>Behaviour: only mutating methods (POST/PUT/PATCH/DELETE) on a mapped capability path are
 * checked; reads are never blocked. The actual decision (native super-admin bypass, binge vs
 * ALL-binge lock, fail-open on outage) lives in {@link AuthorityLockGuard}. When locked we
 * write a {@code 423 Locked} JSON body and stop the request. This is dormant until a
 * super-admin actually places a lock — no behaviour change otherwise.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthorityLockInterceptor implements HandlerInterceptor {

    private final AuthorityLockGuard guard;
    private final ObjectMapper objectMapper;

    /**
     * Path fragment → capability token. First match wins. Fragments are distinct
     * ("/add-ons" never matches "/addon-categories"). Keep in sync with the frontend
     * {@code LOCKABLE_CAPABILITIES} list and {@link AuthorityLockGuard} callers.
     */
    private static final List<Map.Entry<String, String>> CAPABILITY_BY_PATH = List.of(
        Map.entry("/taxes", "TAXES"),
        Map.entry("/event-types", "EVENT_TYPES"),
        Map.entry("/add-ons", "ADD_ONS"),
        Map.entry("/event-categories", "CATEGORIES"),
        Map.entry("/addon-categories", "CATEGORIES"),
        Map.entry("/venue-rooms", "VENUE_ROOMS"),
        Map.entry("/cancellation", "CANCELLATION")
    );

    /** Resolve the lockable capability for a request URI, or {@code null} if none. */
    static String resolveCapability(String uri) {
        if (uri == null) return null;
        for (Map.Entry<String, String> e : CAPABILITY_BY_PATH) {
            if (uri.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {
        if (!isMutating(request.getMethod())) return true;          // reads never blocked
        String capability = resolveCapability(request.getRequestURI());
        if (capability == null) return true;                        // not a lockable capability

        try {
            guard.requireUnlocked(capability, request.getHeader("X-User-Role"));
            return true;
        } catch (BusinessException ex) {
            // Write the 423 directly rather than relying on @ControllerAdvice to resolve
            // exceptions thrown from an interceptor — keeps the contract explicit.
            response.setStatus(ex.getStatus() != null ? ex.getStatus().value() : 423);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ex.getMessage())));
            return false;
        }
    }
}
