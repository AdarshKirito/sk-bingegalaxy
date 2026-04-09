package com.skbingegalaxy.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Guards {@code /internal/**} endpoints with a shared secret header.
 * Only service-to-service Feign calls that include the correct
 * {@code X-Internal-Secret} header are allowed through.
 * Uses constant-time comparison to prevent timing attacks.
 * Sets a SYSTEM authentication on the SecurityContext so Spring Security's
 * {@code .authenticated()} recognises internal calls.
 */
public class InternalApiAuthFilter extends jakarta.servlet.http.HttpFilter {

    private static final String HEADER = "X-Internal-Secret";
    private final String expectedSecret;

    public InternalApiAuthFilter(String expectedSecret) {
        this.expectedSecret = expectedSecret;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();

        if (path.contains("/internal/")) {
            String secret = request.getHeader(HEADER);
            if (expectedSecret == null || expectedSecret.isBlank()
                    || secret == null || secret.isBlank()
                    || !constantTimeEquals(expectedSecret, secret)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("Forbidden");
                return;
            }
            // Set a SYSTEM-level authentication so .authenticated() passes
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "SYSTEM", null,
                    List.of(new SimpleGrantedAuthority("ROLE_SYSTEM")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
