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
public class AdminPolicyTemplateResponse {
    private Integer policyTemplateID;
    private String templateName;
    private String tourPriceIncludes;
    private String tourPriceExcludes;
    private String childPricingNotes;
    private String paymentConditions;
    private String registrationConditions;
    private String regularDayCancellationRules;
    private String holidayCancellationRules;
    private String forceMajeureRules;
    private String packingList;
    private BranchInfo branchInfo;
    private Integer usageCount;
    private Boolean status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BranchInfo {
        private Integer contactID;
        private String branchName;
        private String phone;
        private String email;
    }
}
