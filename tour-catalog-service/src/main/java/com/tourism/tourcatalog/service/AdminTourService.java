package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.admin.CreateTourRequest;
import com.tourism.tourcatalog.dto.request.admin.ItineraryDayRequest;
import com.tourism.tourcatalog.dto.request.admin.TourGeneralInfoRequest;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.dto.response.admin.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminTourService {
    PagedAdminResponse<AdminTourListItem> getAllTours(int page, int size, String sortBy, String sortDir);
    PagedAdminResponse<AdminTourListItem> searchTours(String keyword, int page, int size);
    AdminTourDetailResponse getTourById(Integer tourId);
    AdminTourDetailResponse createTour(CreateTourRequest request);
    AdminTourDetailResponse updateGeneralInfo(Integer tourId, TourGeneralInfoRequest request);
    void updateItinerary(Integer tourId, List<ItineraryDayRequest> days);
    AdminImageResponse uploadImage(Integer tourId, MultipartFile file, boolean isMain);
    AdminMediaResponse uploadMedia(Integer tourId, MultipartFile file, String mediaType);
    void deleteTour(Integer tourId);
    boolean checkTourCodeExists(String tourCode);
    List<LocationResponse> getAllLocations();
}
