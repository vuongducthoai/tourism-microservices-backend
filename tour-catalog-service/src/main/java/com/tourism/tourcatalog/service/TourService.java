package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;

import java.util.List;

public interface TourService {
    /** GET /api/tours/display — tất cả tour active cho trang danh sách */
    List<TourDisplayResponse> getAllToursForDisplay();

    /** GET /api/tours/deepest-discount — top 10 tour có % giảm giá lớn nhất */
    List<TourSpecialResponse> getTop10DeepestDiscountTours();

    /** GET /api/tours/search — tìm kiếm tour với các filter tuỳ chọn */
    List<TourSearchResponse> searchTours(SearchToursRequest request);

    /** GET /api/tours/chatbot-sync — dữ liệu đầy đủ để analytics-service sync lên Pinecone */
    List<TourChatbotSyncResponse> getAllToursForChatbotSync();
}
