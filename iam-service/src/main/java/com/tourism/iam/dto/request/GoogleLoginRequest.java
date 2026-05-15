package com.tourism.iam.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {
    @NotBlank
    private String code;

    @NotBlank
    private String redirectUri;
}
