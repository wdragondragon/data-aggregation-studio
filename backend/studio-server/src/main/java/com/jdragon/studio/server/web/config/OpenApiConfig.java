package com.jdragon.studio.server.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Data Aggregation Studio API",
                version = "0.1.0",
                description = "Web-first backend API for Data Aggregation Studio",
                contact = @Contact(name = "Data Aggregation Studio")
        ),
        security = @SecurityRequirement(name = "Authorization")
)
@SecurityScheme(name = "Authorization", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    private static final String AUTHORIZATION_SCHEME = "Authorization";

    @Bean
    public OpenAPI studioOpenApi() {
        return new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("Data Aggregation Studio API")
                        .version("0.1.0")
                        .description("Knife4j/OpenAPI documentation for Data Aggregation Studio")
                        .license(new License().name("Internal Use")));
    }

    @Bean
    public OpenApiCustomiser studioSecurityCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                if (pathItem == null) {
                    return;
                }
                pathItem.readOperations().forEach(operation -> {
                    if (path.startsWith("/api/v1/auth")) {
                        operation.setSecurity(new java.util.ArrayList<>());
                        return;
                    }
                    if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
                        operation.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList(AUTHORIZATION_SCHEME));
                    }
                });
            });
        };
    }
}
