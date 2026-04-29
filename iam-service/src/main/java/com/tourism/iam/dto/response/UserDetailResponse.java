package com.tourism.iam.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
public class UserDetailResponse {
    private Integer    userID;
    private String     fullName;
    private String     phone;
    private LocalDate  dateOfBirth;
    private String     email;
    private BigDecimal coinBalance;
    private String     avatar;
    private Boolean    status;
    private String     role;
}
