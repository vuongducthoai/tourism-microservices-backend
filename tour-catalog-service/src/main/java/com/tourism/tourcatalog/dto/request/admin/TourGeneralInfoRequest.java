package com.tourism.tourcatalog.dto.request.admin;

import lombok.Data;

@Data
public class TourGeneralInfoRequest {
    private String tourName;
    private String tourCode;
    private String duration;
    private String transportation;
    private Integer startLocationId;
    private Integer endLocationId;
    private String attractions;
    private String meals;
    private String idealTime;
    private String tripTransportation;
    private String suitableCustomer;
    private String hotel;
    private Boolean status = true;
}
