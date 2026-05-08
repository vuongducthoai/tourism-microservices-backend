package com.tourism.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payload received from iam-service when a user is locked/unlocked.
 * notification-service pushes this to WebSocket /topic/admin/users.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
    /** Reason for lock/unlock — used to compose the email body. */
    private String        reason;
}
