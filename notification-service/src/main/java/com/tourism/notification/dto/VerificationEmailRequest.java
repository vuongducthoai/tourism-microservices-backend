package com.tourism.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationEmailRequest implements Serializable {
    private String email;
    private String fullName;
    private String verificationToken;
    private String verificationUrl;
    private String otpCode;
    private Integer otpExpiryMinutes;
}
