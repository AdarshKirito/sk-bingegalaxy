package com.skbingegalaxy.availability.config;

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

@Configuration
@EnableWebSecurity
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
                .requestMatchers("/api/v1/availability/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/availability/dates", "/api/v1/availability/slots", "/api/v1/availability/event-types").permitAll()
                .requestMatchers("/api/v1/availability/internal/**").hasRole("SYSTEM")
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
