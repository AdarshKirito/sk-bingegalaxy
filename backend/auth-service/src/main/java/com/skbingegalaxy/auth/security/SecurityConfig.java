package com.skbingegalaxy.auth.security;

import com.skbingegalaxy.common.security.GatewayHeaderAuthFilter;
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
            .addFilterBefore(new GatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
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
