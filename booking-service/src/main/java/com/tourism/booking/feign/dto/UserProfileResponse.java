package com.tourism.booking.feign.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class UserProfileResponse {
    private Integer userID;
    private String fullName;
    private String email;
    private BigDecimal coinBalance;
}
