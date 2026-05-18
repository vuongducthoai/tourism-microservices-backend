package com.tourism.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity 
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // ─── Public endpoints — không cần token ───
                        .pathMatchers(
                                "/api/auth/**",
                                "/api/users/**",
                                "/api/tours/**",
                                "/api/locations/**",
                                "/api/reviews/**",
                                "/api/branch-contacts/**",
                                "/api/posts/**",
                                "/api/tags/**",
                                "/api/categories/**"
                        ).permitAll()

                        // ─── Swagger / OpenAPI — public ───
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**"
                        ).permitAll()

                        // ─── Actuator health check — public ───
                        .pathMatchers("/actuator/**").permitAll()

                        // ─── WebSocket — public (auth xử lý ở tầng STOMP) ───
                        .pathMatchers("/ws/**").permitAll()

                        // Gateway chỉ route, từng service tự xử lý auth
                        .anyExchange().permitAll()
                )
                .build();
    }
    
}
