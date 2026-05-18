package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDetailResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.dto.response.RelatedTourResponse;

import java.util.List;

public interface TourService {
    List<TourDisplayResponse> getAllToursForDisplay();
    List<TourSpecialResponse> getTop10DeepestDiscountTours();
    List<TourSearchResponse> searchTours(SearchToursRequest request);
    List<TourChatbotSyncResponse> getAllToursForChatbotSync();
    TourDetailResponse getTourDetailByCode(String tourCode);
    List<RelatedTourResponse> getRelatedTours(String tourCode);
}
