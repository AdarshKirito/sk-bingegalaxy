package com.skbingegalaxy.booking.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthorityLockInterceptor} for all admin mutation paths so super-admin
 * capability locks are enforced centrally. Scoped to {@code /api/v1/bookings/admin/**};
 * the interceptor itself no-ops for reads and for non-lockable paths.
 */
@Configuration
@RequiredArgsConstructor
public class AuthorityLockWebConfig implements WebMvcConfigurer {

    private final AuthorityLockInterceptor authorityLockInterceptor;
    private final com.skbingegalaxy.booking.permission.ModulePermissionInterceptor modulePermissionInterceptor;
    private final com.skbingegalaxy.booking.permission.BingeApprovalInterceptor bingeApprovalInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // Freeze REJECTED venues to lifecycle-only writes. Registered FIRST so a
        // rejected-venue write is refused before any module/capability evaluation.
        registry.addInterceptor(bingeApprovalInterceptor)
            .addPathPatterns(
                "/api/v1/bookings/admin/**",
                "/api/v1/bookings/waitlist/admin/**",
                "/api/v1/bookings/slot-holds/admin/**",
                "/api/v1/bookings/pricing/**");
        registry.addInterceptor(authorityLockInterceptor)
            .addPathPatterns("/api/v1/bookings/admin/**");
        // Per-binge module matrix (super-admin enable/disable/lock per admin).
        // Registered on every admin path group that maps to a menu module; the
        // interceptor itself no-ops for unmapped paths and for SUPER_ADMIN.
        registry.addInterceptor(modulePermissionInterceptor)
            .addPathPatterns(
                "/api/v1/bookings/admin/**",
                "/api/v1/bookings/waitlist/admin/**",
                "/api/v1/bookings/slot-holds/admin/**");
    }
}
