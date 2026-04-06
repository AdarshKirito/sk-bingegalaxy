package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.common.context.BingeContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feign interceptor that forwards the current bingeId (from BingeContext)
 * to downstream service calls and attaches the internal API secret
 * for service-to-service authentication.
 */
@Component
public class BingeFeignInterceptor implements RequestInterceptor {

    @Value("${internal.api.secret:}")
    private String internalApiSecret;

    @Override
    public void apply(RequestTemplate template) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId != null) {
            template.query("bingeId", bingeId.toString());
        }
        if (internalApiSecret != null && !internalApiSecret.isBlank()) {
            template.header("X-Internal-Secret", internalApiSecret);
        }
    }
}
