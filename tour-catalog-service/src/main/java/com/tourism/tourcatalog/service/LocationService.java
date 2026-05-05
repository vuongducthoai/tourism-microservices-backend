package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;

import java.util.List;

public interface LocationService {
    /** GET /api/locations/start-location — các điểm khởi hành của tour active */
    List<LocationResponse> getStartLocations();

    /** GET /api/locations/end-location — các điểm đến của tour active */
    List<LocationResponse> getEndLocations();

    /**
     * POST /api/locations/destinations-by-region — địa điểm nổi bật theo vùng miền.
     * @throws IllegalArgumentException nếu region không hợp lệ -> 400 Bad Request
     */
    List<DestinationResponse> getDestinationsByRegion(RegionRequest request);

    /** GET /api/locations/chatbot-sync — tất cả điểm đến active với đầy đủ thông tin */
    List<LocationChatbotSyncResponse> getAllLocationsForChatbotSync();
}
