package com.devmate.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI devMateOpenApi() {
        return new OpenAPI().info(new Info()
                .title("DevMate API")
                .description("Backend APIs for the DevMate development assistant platform")
                .version("v1"));
    }
}
