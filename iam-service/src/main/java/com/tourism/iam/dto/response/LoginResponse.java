package com.tourism.iam.dto.response;



import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private Long expiredIn;
    private UserInfo user;

    @Data
    @Builder
    public static class UserInfo { // static because you don't need an instance of LoginResponse to use it
        private Integer userId;
        private String fullName;
        private String email;
        private String avatar;
        private String role;
        private String provinceName;
        private String districtName;
        private BigDecimal coinBalance;
    }
}


