package com.skbingegalaxy.payment.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration that stamps the shared {@code X-Internal-Secret}
 * header so booking-service's InternalApiAuthFilter grants ROLE_SYSTEM.
 *
 * <p>Deliberately NOT annotated {@code @Configuration}: Feign instantiates it in
 * the client's child context, so the interceptor applies only to clients that
 * reference it via {@code @FeignClient(configuration = ...)} — a global
 * annotation would leak the secret onto every outbound Feign call.</p>
 */
public class InternalApiFeignConfig {

    @Bean
    public RequestInterceptor internalSecretInterceptor(
            @Value("${internal.api.secret}") String internalApiSecret) {
        return template -> template.header("X-Internal-Secret", internalApiSecret);
    }
}
