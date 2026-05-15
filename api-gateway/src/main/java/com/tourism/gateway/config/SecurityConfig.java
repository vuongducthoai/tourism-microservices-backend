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
                        // ─── Auth endpoints — public (không cần token) ───
                        .pathMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh-token",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification"
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

                        // ─── Tất cả route còn lại — phải có token hợp lệ ───
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveKeycloakJwtConverter()))
                )
                .build();
    }
    
}
