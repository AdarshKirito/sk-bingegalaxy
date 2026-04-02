package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.common.context.BingeContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

/**
 * Feign interceptor that forwards the current bingeId (from BingeContext)
 * to downstream service calls as a query parameter.
 */
@Component
public class BingeFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId != null) {
            template.query("bingeId", bingeId.toString());
        }
    }
}
