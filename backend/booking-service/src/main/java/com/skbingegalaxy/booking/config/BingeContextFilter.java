package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.common.context.BingeContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that extracts bingeId from request param or header
 * and stores it in BingeContext (ThreadLocal) for the duration of the request.
 */
@Component
@Order(1)
public class BingeContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            String bingeIdStr = httpReq.getParameter("bingeId");
            if (bingeIdStr == null || bingeIdStr.isBlank()) {
                bingeIdStr = httpReq.getHeader("X-Binge-Id");
            }
            if (bingeIdStr != null && !bingeIdStr.isBlank()) {
                try {
                    BingeContext.setBingeId(Long.parseLong(bingeIdStr));
                } catch (NumberFormatException ignored) { }
            }
            chain.doFilter(request, response);
        } finally {
            BingeContext.clear();
        }
    }
}
