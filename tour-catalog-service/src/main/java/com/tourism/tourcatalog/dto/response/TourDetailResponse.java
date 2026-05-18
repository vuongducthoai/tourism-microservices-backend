package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourDetailResponse {
    private Integer tourId;
    private String tourCode;
    private String tourName;
    private String duration;
    private String transportation;
    private String startLocation;
    private String endLocation;
    private String attractions;
    private String meals;
    private String suitableCustomer;
    private String idealTime;
    private String tripTransportation;
    private String hotel;
    private List<String> images;
    private String videoUrl;
    private List<ItineraryDayResponse> itinerary;
    private List<DepartureDetailResponse> departures;
    private PolicyTemplateResponse policy;
    private BranchContactResponse branchContact;
    private BigDecimal originalPrice;
    private BigDecimal salePrice;
    private Integer totalDiscountPercentage;
}
