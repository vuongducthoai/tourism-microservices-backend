package com.tourism.iam.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RequestTokenRequest {
    @NotBlank
    private String refreshToken;
}
