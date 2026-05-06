package com.tourism.booking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sepay")
@Data
public class SepayConfig {
    private String apiUrl;
    private String token;
    private String accountNumber;
    private String accountName;
    private String bankCode;
}
