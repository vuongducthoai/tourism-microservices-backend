package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchContactResponse {
    private String branchName;
    private String address;
    private String phone;
    private String email;
    private Boolean isHeadOffice;
}
