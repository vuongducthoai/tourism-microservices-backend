package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.admin.CreateDepartureRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureDetailResponse;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureListItem;

import java.util.Map;

public interface AdminDepartureService {
    Map<String, Object> getAllDepartures(int page, int size, Boolean status, String startDate, String endDate);
    Map<String, Object> getGroupedDepartures(int page, int size, Boolean status, String startDate, String endDate);
    AdminDepartureDetailResponse getDepartureById(Integer id);
    AdminDepartureDetailResponse createDeparture(CreateDepartureRequest request);
    AdminDepartureDetailResponse updateDeparture(Integer id, CreateDepartureRequest request);
    void deleteDeparture(Integer id);
    AdminDepartureDetailResponse cloneDeparture(Integer id, String newDepartureDate);
}
