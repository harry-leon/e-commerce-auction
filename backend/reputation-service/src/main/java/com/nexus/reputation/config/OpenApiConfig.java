package com.nexus.reputation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình OpenAPI/Swagger UI bổ sung cơ chế xác thực Bearer JWT để kiểm thử các endpoint cần đăng nhập.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String name) {
        return new OpenAPI()
                .info(new Info().title(name).version("v1"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
