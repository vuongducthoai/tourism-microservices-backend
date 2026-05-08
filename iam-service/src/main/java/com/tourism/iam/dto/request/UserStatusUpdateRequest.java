package com.tourism.iam.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserStatusUpdateRequest {
    private Integer userID;
    private Boolean status;
    private String  reason;
}
