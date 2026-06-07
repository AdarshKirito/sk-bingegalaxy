package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.common.security.GatewayHeaderAuthFilter;
import com.skbingegalaxy.common.security.InternalApiAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new InternalApiAuthFilter(internalApiSecret), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new GatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/bookings/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/bookings/waitlist/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/bookings/internal/**").hasRole("SYSTEM")
                // Loyalty v2 super-admin endpoints — program-wide config (tier ladder,
                // perks catalogue, program metadata). MUST be SUPER_ADMIN; the gateway
                // also enforces this but we duplicate here as defence in depth so that
                // any direct service-mesh access still requires the role.
                .requestMatchers("/api/v2/loyalty/super-admin/**").hasRole("SUPER_ADMIN")
                // Loyalty v2 admin endpoints — per-binge bindings, earn/redeem rules,
                // status-match approvals. ADMIN or SUPER_ADMIN.
                .requestMatchers("/api/v2/loyalty/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers(
                    "/api/v1/bookings/binges/**",
                    "/api/v1/bookings/event-types",
                    "/api/v1/bookings/add-ons",
                    "/api/v1/bookings/booked-slots",
                    "/api/v1/bookings/slot-capacity",
                    "/api/v1/bookings/venue-rooms",
                    "/api/v1/bookings/venue-rooms/available",
                    "/api/v1/bookings/surge-rules",
                    // Funnel analytics ingest — wizard fires this for guests too.
                    // Controller is documented as "unauthenticated-friendly".
                    "/api/v1/bookings/analytics/funnel",
                    // Booking-transfer recipient endpoints (magic-link pattern).
                    "/api/v1/booking-transfers/by-token/**"
                ).permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers("/actuator/**").hasRole("SYSTEM")
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
