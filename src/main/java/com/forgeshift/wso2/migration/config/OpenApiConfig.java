package com.forgeshift.wso2.migration.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI apiDocs() {
        return new OpenAPI().info(new Info()
                .title("Forgeshift WSO2 to Kong Konnect Migration Service")
                .version("0.1.0")
                .description("Translates WSO2 snapshots into Kong Konnect entities and deploys them via Admin API."));
    }
}
