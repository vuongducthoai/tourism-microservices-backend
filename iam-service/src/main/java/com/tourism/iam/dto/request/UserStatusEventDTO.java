package com.tourism.iam.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payload sent from iam-service → notification-service
 * so notification-service can push the user update to WebSocket /topic/admin/users.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusEventDTO {
    private Integer       userID;
    private String        fullName;
    private String        phone;
    private String        email;
    private String        avatar;
    private BigDecimal    coinBalance;
    private LocalDate     dateOfBirth;
    private Boolean       status;
    private String        role;
    private LocalDateTime lastActiveAt;
    private String        activityStatus;
    /** Reason for lock/unlock — forwarded to notification-service so it can include it in the email. */
    private String        reason;
}
