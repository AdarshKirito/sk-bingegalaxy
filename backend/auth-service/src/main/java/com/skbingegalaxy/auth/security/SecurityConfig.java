package com.skbingegalaxy.auth.security;

import com.skbingegalaxy.common.security.GatewayHeaderAuthFilter;
import com.skbingegalaxy.common.security.InternalApiAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF is intentionally disabled: this service is stateless
            // (no server-side sessions, no cookie-based CSRF tokens). The
            // browser-facing entry point is the API gateway, which only
            // forwards requests carrying a JWT bearer / HttpOnly cookie
            // bound to a CORS-restricted origin (see GatewayConfig). If a
            // future feature reintroduces session-based auth, CSRF MUST be
            // re-enabled here.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // InternalApiAuthFilter MUST run before GatewayHeaderAuthFilter so
            // the shared-secret check on /internal/** rejects external callers
            // (no header / wrong header → 403) before the gateway-trust filter
            // would otherwise see them as anonymous. Service-to-service Feign
            // calls supplying X-Internal-Secret get a SYSTEM authentication
            // and pass the hasRole("SYSTEM") matcher below.
            .addFilterBefore(new InternalApiAuthFilter(internalApiSecret), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new GatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Authority Handover internal lock lookup — consulted by
                // downstream services (booking-service AdminCurrencyController,
                // etc.) to enforce locks server-side. Must come BEFORE the
                // generic /api/v1/auth/** permitAll rule so the SYSTEM role
                // requirement is honoured (defence in depth on top of the
                // shared-secret filter above).
                .requestMatchers("/api/v1/auth/authority/internal/**").hasRole("SYSTEM")
                .requestMatchers("/api/v1/auth/admin/login").permitAll()
                .requestMatchers("/api/v1/auth/admin/register").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/user/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/users/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/bulk-delete").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/admins/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/admins").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/sessions/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/audit-log").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/super-admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/site-content/public/**").permitAll()
                .requestMatchers("/api/v1/site-content/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers("/actuator/**").hasRole("SYSTEM")
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
