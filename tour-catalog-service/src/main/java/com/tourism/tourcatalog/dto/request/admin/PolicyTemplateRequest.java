package com.tourism.tourcatalog.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PolicyTemplateRequest {

    @NotBlank(message = "Tên template không được để trống")
    private String templateName;

    @NotNull(message = "Chi nhánh không được để trống")
    private Integer contactId;

    private String tourPriceIncludes;
    private String tourPriceExcludes;
    private String childPricingNotes;
    private String paymentConditions;
    private String registrationConditions;
    private String regularDayCancellationRules;
    private String holidayCancellationRules;
    private String forceMajeureRules;
    private String packingList;
}
