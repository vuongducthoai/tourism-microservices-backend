package com.tourism.iam.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserSearchRequest {
    private String fullName;
    private String phone;
    private String email;
}
