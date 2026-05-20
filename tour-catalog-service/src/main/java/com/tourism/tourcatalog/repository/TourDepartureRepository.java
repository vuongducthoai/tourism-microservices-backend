package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.TourDeparture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TourDepartureRepository extends JpaRepository<TourDeparture, Integer> {

    /**
     * Lấy các departure active, ngày >= hôm nay, có giảm giá (originalPrice > salePrice).
     * Dùng cho GET /api/tours/deepest-discount.
     *
     * departureDate trong entity microservices là LocalDate (map cột DATE của DB).
     * DeparturePricing.salePrice map sang cột "price" của DB (xem @Column(name="price")).
     */
    @Query("""
            SELECT DISTINCT d FROM TourDeparture d
            JOIN FETCH d.tour t
            LEFT JOIN FETCH t.startLocation
            LEFT JOIN FETCH d.pricings
            WHERE d.status = true
              AND d.departureDate >= :today
              AND EXISTS (
                SELECT p FROM DeparturePricing p
                WHERE p.tourDeparture = d
                  AND p.passengerType = 'ADULT'
                  AND p.originalPrice > p.salePrice
              )
            """)
    List<TourDeparture> findActiveDiscountedDepartures(@Param("today") LocalDateTime today);

    @Modifying
    @Query("""
            UPDATE TourDeparture d
            SET d.availableSlots = d.availableSlots - :count
            WHERE d.departureID = :departureId
            AND d.availableSlots >= :count
            """)
    int decreaseAvailableSlots(@Param("departureId") Integer departureId, @Param("count") int count);

    @Modifying
    @Query("""
            UPDATE TourDeparture d
            SET d.availableSlots = d.availableSlots + :count
            WHERE d.departureID = :departureId
            """)
    int increaseAvailableSlots(@Param("departureId") Integer departureId, @Param("count") int count);

    Page<TourDeparture> findAllByIsDeletedFalse(Pageable pageable);

    Page<TourDeparture> findAllByStatusAndIsDeletedFalse(Boolean status, Pageable pageable);

    Page<TourDeparture> findAllByIsDeletedFalseAndDepartureDateBetween(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Page<TourDeparture> findAllByStatusAndIsDeletedFalseAndDepartureDateBetween(
            Boolean status, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);
}
