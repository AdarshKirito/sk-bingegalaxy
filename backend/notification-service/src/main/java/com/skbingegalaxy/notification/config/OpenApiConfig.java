package com.skbingegalaxy.notification.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Notification Service API",
        description = "Notification management endpoints for SK Binge Galaxy",
        version = "1.0.0"
    )
)
public class OpenApiConfig {
}
