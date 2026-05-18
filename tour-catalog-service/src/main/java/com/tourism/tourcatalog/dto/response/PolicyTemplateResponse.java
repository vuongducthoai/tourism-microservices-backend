package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyTemplateResponse {
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
}
