package com.skbingegalaxy.distribution.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the tenancy boundary for the venue-facing distribution API.
 *
 * <p>The pattern is the whole surface, with the interceptor itself deciding what to skip
 * (see {@link DistributionAccessInterceptor#isVenueFacing}). Excluding paths here instead
 * would put the rule in two places, and the half that gets forgotten is always the one
 * that leaves an endpoint unguarded.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final DistributionAccessInterceptor distributionAccessInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(distributionAccessInterceptor)
            .addPathPatterns("/api/v1/distribution/**");
    }
}
