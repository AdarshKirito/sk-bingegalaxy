package com.skbingegalaxy.availability.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Availability Service API",
        description = "Slot availability and event type management for SK Binge Galaxy",
        version = "1.0.0"
    )
)
public class OpenApiConfig {
}
