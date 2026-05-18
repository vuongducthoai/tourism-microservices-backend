package com.tourism.iam.config;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.beans.factory.annotation.Value;


@Configuration
public class KeycloackConfig {

    @Value("${keycloak.server-url}")
    private String severUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.admin-username}")
    private String adminUsername;

    @Value("${keycloak.admin-password}")
    private String adminPassword;
    

    @Bean
    public Keycloak keycloakAdminClient() {
    // Create a Keycloak admin client using the admin account in master realm
    // Must use master realm with admin-cli client to access admin API
        return KeycloakBuilder.builder()
                .serverUrl(severUrl)
                .realm("master")
                .clientId("admin-cli")
                .username(adminUsername)
                .password(adminPassword)
                .grantType("password")
                .build();
    }
}
