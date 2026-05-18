package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.request.admin.LocationRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.dto.response.admin.AdminLocationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface LocationService {
    List<LocationResponse> getStartLocations();
    List<LocationResponse> getEndLocations();
    List<DestinationResponse> getDestinationsByRegion(RegionRequest request);
    List<LocationChatbotSyncResponse> getAllLocationsForChatbotSync();
    Page<LocationResponse> getNationalLocations(int page, int size);

    // Admin CRUD
    Page<AdminLocationResponse> getAllLocations(Pageable pageable, String search, String region);
    Page<AdminLocationResponse> getAirportNational(Pageable pageable);
    AdminLocationResponse getLocationById(Integer id);
    AdminLocationResponse createLocation(LocationRequest request);
    AdminLocationResponse updateLocation(Integer id, LocationRequest request);
    void deleteLocation(Integer id);
    String uploadImage(Integer id, MultipartFile file) throws IOException;
}
