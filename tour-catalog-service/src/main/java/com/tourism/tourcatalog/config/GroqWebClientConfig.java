package com.tourism.tourcatalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * WebClient dùng JDK HttpClient (bypass Netty DNS bug trong Docker).
 * Dùng cho mọi external HTTP call (Groq AI review summary).
 */
@Configuration
public class GroqWebClientConfig {

    @Bean(name = "groqWebClient")
    public WebClient groqWebClient() {
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(jdkClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }
}
