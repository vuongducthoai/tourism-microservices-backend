package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBranchContactResponse {
    private Integer contactID;
    private String branchName;
    private String phone;
    private String email;
    private String address;
    private Boolean isHeadOffice;
    private Integer policyCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
