package com.tourism.tourcatalog.feign.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * User profile info received from iam-service via Feign.
 * Used to enrich review listing responses with user fullName and avatar.
 */
@Data
@NoArgsConstructor
public class UserBriefResponse {
    private Integer    userID;
    private String     fullName;
    private String     avatar;
    private String     email;
    private BigDecimal coinBalance;
}
