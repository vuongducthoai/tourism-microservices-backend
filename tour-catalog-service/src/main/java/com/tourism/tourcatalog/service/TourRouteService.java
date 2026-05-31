package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.TourStopRequest;
import com.tourism.tourcatalog.dto.response.TourRouteResponse;
import com.tourism.tourcatalog.dto.response.TourStopResponse;

import java.util.List;

public interface TourRouteService {

    /** Public: lấy route theo tourCode. Trả response trống nếu tour chưa có stop. */
    TourRouteResponse getRoute(String tourCode);

    /** Admin: list stop hiện có. */
    List<TourStopResponse> getStopsByTourId(Integer tourId);

    /** Admin: thay thế toàn bộ stop của tour (xóa cũ + lưu mới, atomic). */
    List<TourStopResponse> upsertStops(Integer tourId, List<TourStopRequest> stops);

    /** Admin: xóa 1 stop. */
    void deleteStop(Integer stopId);
}
