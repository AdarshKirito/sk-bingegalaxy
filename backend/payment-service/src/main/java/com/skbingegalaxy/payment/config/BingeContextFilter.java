package com.skbingegalaxy.payment.config;

import com.skbingegalaxy.common.context.BingeContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
@Slf4j
public class BingeContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String bingeId = httpRequest.getParameter("bingeId");
            if (bingeId == null || bingeId.isBlank()) {
                bingeId = httpRequest.getHeader("X-Binge-Id");
            }

            if (bingeId != null && !bingeId.isBlank()) {
                try {
                    BingeContext.setBingeId(Long.parseLong(bingeId));
                } catch (NumberFormatException exception) {
                    log.warn("Invalid bingeId '{}': {}", bingeId, exception.getMessage());
                }
            }

            chain.doFilter(request, response);
        } finally {
            BingeContext.clear();
        }
    }
}