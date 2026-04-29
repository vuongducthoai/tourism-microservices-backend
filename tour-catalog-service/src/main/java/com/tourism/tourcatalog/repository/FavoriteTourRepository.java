package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.FavoriteTour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FavoriteTourRepository extends JpaRepository<FavoriteTour, Integer> {

    @Query("""
            SELECT f FROM FavoriteTour f
            JOIN FETCH f.tour t
            LEFT JOIN FETCH t.startLocation
            WHERE f.userId = :userId AND t.status = true
            ORDER BY f.createdAt DESC
            """)
    List<FavoriteTour> findByUserIdWithTourDetails(@Param("userId") Integer userId);

    boolean existsByUserIdAndTour_TourID(Integer userId, Integer tourId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FavoriteTour f WHERE f.userId = :userId AND f.tour.tourID = :tourId")
    void deleteByUserIdAndTourId(@Param("userId") Integer userId, @Param("tourId") Integer tourId);
}
