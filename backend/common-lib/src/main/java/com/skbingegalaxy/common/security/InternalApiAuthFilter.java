package com.skbingegalaxy.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Guards {@code /internal/**} endpoints with a shared secret header.
 * Only service-to-service Feign calls that include the correct
 * {@code X-Internal-Secret} header are allowed through.
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
                    || !expectedSecret.equals(secret)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("Forbidden");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
