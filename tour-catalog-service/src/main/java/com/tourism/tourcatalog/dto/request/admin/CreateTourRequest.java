package com.tourism.tourcatalog.dto.request.admin;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CreateTourRequest {
    private TourGeneralInfoRequest generalInfo;
    private List<ItineraryDayRequest> itineraryDays = new ArrayList<>();
}
