package com.jdragon.studio.flink.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Data Aggregation Studio Flink API",
                version = "0.1.0",
                description = "Web-first backend API for Data Aggregation Studio Flink",
                contact = @Contact(name = "Data Aggregation Studio Flink")
        ),
        security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "Authorization")
)
@SecurityScheme(name = "Authorization", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")

public class FlinkOpenApiConfig {

    private static final String AUTHORIZATION_SCHEME = "Authorization";

    @Bean
    public OpenApiCustomizer studioSecurityCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                if (pathItem == null) {
                    return;
                }
                pathItem.readOperations().forEach(operation -> {
                    if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
                        operation.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList(AUTHORIZATION_SCHEME));
                    }
                });
            });
        };
    }

    @Bean
    public GroupedOpenApi flinkGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("studio-flink")
                .packagesToScan("com.jdragon.studio.flink.web.controller")
                .pathsToMatch("/api/flink/**")
                .build();
    }
}
