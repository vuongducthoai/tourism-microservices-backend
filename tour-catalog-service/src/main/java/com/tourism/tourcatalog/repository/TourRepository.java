package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.Tour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TourRepository extends JpaRepository<Tour, Integer>, TourRepositoryCustom {

    /**
     * Lấy tất cả tour active kèm endLocation, startLocation, departures.
     * Chỉ JOIN FETCH 1 collection (departures) — tránh MultipleBagFetchException.
     * images và pricings được lazy-load trong @Transactional service.
     */
    @Query("""
            SELECT DISTINCT t FROM Tour t
            LEFT JOIN FETCH t.endLocation
            LEFT JOIN FETCH t.startLocation
            LEFT JOIN FETCH t.departures
            WHERE t.status = true
            """)
    List<Tour> findAllActiveWithDetails();

    // ─── Dashboard stats queries ───

    Long countByStatus(Boolean status);

    @Query("SELECT COUNT(td) FROM TourDeparture td WHERE (td.tour.isDeleted = false OR td.tour.isDeleted IS NULL)")
    Long countAllDepartures();

    @Query("SELECT COUNT(td) FROM TourDeparture td WHERE td.departureDate > :now AND td.status = true AND (td.tour.isDeleted = false OR td.tour.isDeleted IS NULL)")
    Long countUpcomingDepartures(@Param("now") LocalDateTime now);
}
