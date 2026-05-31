package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.response.CombinedItineraryRouteResponse;

public interface ItineraryRouteService {
    CombinedItineraryRouteResponse getCombined(String tourCode);
}
