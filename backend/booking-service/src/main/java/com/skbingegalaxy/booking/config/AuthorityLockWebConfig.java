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

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(authorityLockInterceptor)
            .addPathPatterns("/api/v1/bookings/admin/**");
    }
}
