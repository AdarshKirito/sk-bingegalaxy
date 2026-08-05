package com.skbingegalaxy.distribution.config;

import com.skbingegalaxy.common.security.GatewayHeaderAuthFilter;
import com.skbingegalaxy.common.security.InternalApiAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security for the distribution context.
 *
 * <p><b>Why this exists even though the service has no controllers yet.</b>
 * {@code spring-boot-starter-security} is on the classpath, so without an explicit
 * chain Spring Boot applies its default: HTTP Basic over <em>everything</em> with a
 * random password printed to the log at startup. That would 401 the Dockerfile's
 * {@code HEALTHCHECK} against {@code /actuator/health} and leave the container
 * permanently unhealthy — a failure that looks like a broken service rather than a
 * missing bean. Every other service in this repo carries its own {@code SecurityConfig};
 * this one was the exception.
 *
 * <p><b>{@code anyRequest().denyAll()} is deliberate.</b> The usual
 * {@code authenticated()} would mean that the first controller added to this service is
 * reachable by any authenticated caller until somebody remembers to add a matcher.
 * Denying by default makes a forgotten rule a visible 403 during development instead of
 * an accidentally public endpoint in production. Add matchers above it as each slice
 * lands.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless service-to-service traffic; no browser session, no CSRF token
            // to protect. Matches the other non-gateway services.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new InternalApiAuthFilter(internalApiSecret),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new GatewayHeaderAuthFilter(),
                UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // The container healthcheck and Kubernetes probes call these unauthenticated.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("SYSTEM")
                // Internal seam: reachable only with the shared secret, and the gateway
                // 404s /internal/** from the internet and strips X-Internal-Secret.
                // Listed BEFORE the venue rules below so a path like
                // /api/v1/distribution/internal/... can never be matched by them first.
                .requestMatchers("/api/v1/distribution/internal/**").hasRole("SYSTEM")
                // Slice 3 — venue-facing connection management. A connection is a
                // venue's commercial relationship with a provider, so ADMIN of that
                // venue is the right level; the per-venue boundary itself is enforced in
                // the service from the gateway's X-Binge-Id, never from the request.
                .requestMatchers("/api/v1/distribution/providers",
                                 "/api/v1/distribution/connections/**",
                                 "/api/v1/distribution/connections")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().denyAll()
            );
        return http.build();
    }
}
