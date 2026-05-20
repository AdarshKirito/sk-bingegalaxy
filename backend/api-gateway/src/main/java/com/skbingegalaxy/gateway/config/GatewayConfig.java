package com.skbingegalaxy.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class GatewayConfig {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Browsers refuse to send custom request headers across CORS unless they
        // appear in the preflight Access-Control-Allow-Headers response. We must
        // explicitly allow Idempotency-Key (Stripe-style request dedupe) and
        // X-CSRF-Token (CSRF double-submit cookie) for cross-origin deployments.
        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "X-Binge-Id",
            "X-Requested-With",
            "X-CSRF-Token",
            "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
