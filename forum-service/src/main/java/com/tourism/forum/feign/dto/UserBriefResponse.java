package com.tourism.forum.feign.dto;

import lombok.Data;

@Data
public class UserBriefResponse {
    private Integer userID;
    private String fullName;
    private String email;
    private String avatar;
    private String role;
    private java.math.BigDecimal coinBalance; // dùng cho thu hồi coin (clawback)
}
