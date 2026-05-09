package com.tourism.tourcatalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tourCatalogOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tour Catalog Service API")
                        .description("Quản lý tour du lịch, điểm đến, lịch khởi hành, đánh giá và tour yêu thích")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Future Travel")
                                .email("support@futuretravel.vn")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("API Gateway"),
                        new Server().url("http://localhost:8082").description("Direct")));
    }
}
