package com.tourism.iam.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Extended user response for admin pages.
 * Includes lastActiveAt and computed activityStatus (Online/Away/Offline).
 */
@Data
@NoArgsConstructor
public class UserAdminResponse {
    private Integer       userID;
    private String        fullName;
    private String        phone;
    private LocalDate     dateOfBirth;
    private String        email;
    private BigDecimal    coinBalance;
    private String        avatar;
    private Boolean       status;
    private String        role;
    private LocalDateTime lastActiveAt;
    private String        activityStatus; // "Online" / "Away" / "Offline"
}
