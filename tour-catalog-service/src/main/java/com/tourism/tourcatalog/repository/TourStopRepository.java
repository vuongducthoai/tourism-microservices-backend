package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.TourStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TourStopRepository extends JpaRepository<TourStop, Integer> {

    @Query("SELECT s FROM TourStop s LEFT JOIN FETCH s.itineraryDay " +
           "WHERE s.tour.tourID = :tourId " +
           "ORDER BY COALESCE(s.itineraryDay.dayNumber, 0), s.stopOrder")
    List<TourStop> findByTourIdOrdered(@Param("tourId") Integer tourId);

    @Query("SELECT s FROM TourStop s LEFT JOIN FETCH s.itineraryDay " +
           "WHERE s.tour.tourCode = :tourCode " +
           "ORDER BY COALESCE(s.itineraryDay.dayNumber, 0), s.stopOrder")
    List<TourStop> findByTourCodeOrdered(@Param("tourCode") String tourCode);

    void deleteByTour_TourID(Integer tourId);
}
