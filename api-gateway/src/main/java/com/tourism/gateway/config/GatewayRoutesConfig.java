package com.tourism.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // IAM Service
                .route("iam-service", r -> r
                        .path("/api/auth/**", "/api/admin/auth/**", "/api/users/**", "/api/admin/profile/**")
                        .uri("lb://iam-service"))

                // Tour Catalog Service
                .route("tour-catalog-service", r -> r
                        .path("/api/tours/**", "/api/admin/tours/**", "/api/locations/**",
                              "/api/admin/locations/**", "/api/departures/**", "/api/reviews/**",
                              "/api/favorite-tours/**", "/api/branch-contacts/**",
                              "/api/policy-templates/**", "/api/tour-media/**")
                        .uri("lb://tour-catalog-service"))

                // Booking Service
                .route("booking-service", r -> r
                        .path(
                                "/api/bookings/**",
                                "/api/coupons/**",
                                "/api/admin/bookings/**",
                                "/api/admin/coupons/**",
                                "/api/coin-withdrawals/**"
                        )
                        .uri("lb://booking-service"))

                // Payment Service
                .route("payment-service", r -> r
                        .path("/api/payment/**")
                        .uri("lb://payment-service"))

                // Forum Service
                .route("forum-service", r -> r
                        .path("/api/posts/**", "/api/tags/**", "/api/categories/**",
                              "/api/bookmarks/**", "/api/followers/**")
                        .uri("lb://forum-service"))

                // Notification Service
                .route("notification-service", r -> r
                        .path("/api/notifications/**")
                        .uri("lb://notification-service"))

                // Notification Service WebSocket
                .route("notification-service-websocket", r -> r
                        .path("/ws/**")
                        .uri("lb:ws://notification-service"))

                // Analytics Service
                .route("analytics-service", r -> r
                        .path("/api/dashboard/**", "/api/chatbot/**")
                        .uri("lb://analytics-service"))

                .build();
    }
}