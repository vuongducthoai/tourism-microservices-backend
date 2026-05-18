package com.tourism.tourcatalog.dto.response.admin;

import com.tourism.tourcatalog.dto.response.ItineraryDayResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminTourDetailResponse {
    private Integer tourID;
    private String tourCode;
    private String tourName;
    private String duration;
    private String transportation;
    private Integer startLocationId;
    private String startLocationName;
    private Integer endLocationId;
    private String endLocationName;
    private String attractions;
    private String meals;
    private String idealTime;
    private String tripTransportation;
    private String suitableCustomer;
    private String hotel;
    private Boolean status;
    private List<AdminImageResponse> images;
    private List<AdminMediaResponse> mediaList;
    private List<ItineraryDayResponse> itineraryDays;
}
