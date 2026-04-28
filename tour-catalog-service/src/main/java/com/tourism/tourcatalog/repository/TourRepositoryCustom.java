package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.entity.Tour;

import java.util.List;

/**
 * Custom repository interface cho tìm kiếm tour động (các filter tùy chọn).
 * Implement bởi TourRepositoryCustomImpl dùng EntityManager + JPQL.
 */
public interface TourRepositoryCustom {
    List<Tour> searchToursDynamically(SearchToursRequest dto);
}
