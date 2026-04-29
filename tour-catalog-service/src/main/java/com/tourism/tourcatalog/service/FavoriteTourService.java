package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.response.TourSearchResponse;

import java.util.List;

public interface FavoriteTourService {

    List<TourSearchResponse> getFavoriteTours(Integer userId);

    void addFavoriteTour(Integer userId, Integer tourId);

    void removeFavoriteTour(Integer userId, Integer tourId);
}
