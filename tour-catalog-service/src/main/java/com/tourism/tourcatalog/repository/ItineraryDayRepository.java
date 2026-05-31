package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.ItineraryDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItineraryDayRepository extends JpaRepository<ItineraryDay, Integer> {

    @Query("SELECT d FROM ItineraryDay d WHERE d.tour.tourID = :tourId AND d.dayNumber = :dayNumber")
    Optional<ItineraryDay> findByTourIdAndDayNumber(@Param("tourId") Integer tourId,
                                                     @Param("dayNumber") Integer dayNumber);

    @Query("SELECT d FROM ItineraryDay d WHERE d.tour.tourID = :tourId ORDER BY d.dayNumber")
    List<ItineraryDay> findByTourIdOrdered(@Param("tourId") Integer tourId);
}
