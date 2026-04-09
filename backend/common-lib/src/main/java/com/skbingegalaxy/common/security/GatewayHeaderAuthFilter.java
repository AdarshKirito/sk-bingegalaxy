package com.skbingegalaxy.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

/**
 * Reads X-User-Id / X-User-Role headers set by the API-Gateway
 * and populates the Spring Security context so downstream
 * {@code SecurityFilterChain} matchers (hasRole, hasAuthority) work.
 */
public class GatewayHeaderAuthFilter extends jakarta.servlet.http.HttpFilter {

    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of(
        "CUSTOMER", "ADMIN", "SUPER_ADMIN");

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");

        if (userId != null && role != null && VALID_ROLES.contains(role)) {
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
