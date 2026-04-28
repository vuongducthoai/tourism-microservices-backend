package com.tourism.tourcatalog.repository.impl;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.repository.TourRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tìm kiếm tour động với các filter tùy chọn — dùng JPQL + EntityManager.
 *
 * Logic từng filter:
 *  - searchNameTour     : LIKE '%name%' (case-insensitive), null = bỏ qua
 *  - startLocationID    : exact match, null = bỏ qua
 *  - endLocationID      : exact match, null = bỏ qua
 *  - transportation     : LIKE '%transport%', null/"" = bỏ qua
 *  - rating             : AVG(review.rating) >= rating, null/0 = bỏ qua
 *  - startPrice/endPrice: MIN(ADULT price) trong khoảng [start, end]
 *                         null = không lọc theo giá đó
 *
 * JOIN FETCH chỉ lấy endLocation, startLocation, departures (1 bag).
 * images và pricings được lazy-load trong @Transactional service.
 */
@Repository
@Transactional(readOnly = true)
public class TourRepositoryCustomImpl implements TourRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Tour> searchToursDynamically(SearchToursRequest dto) {
        // Chuẩn hóa: rỗng/blank -> null để JPQL bỏ qua filter
        String nameParam = (dto.getSearchNameTour() != null && !dto.getSearchNameTour().isBlank())
                ? dto.getSearchNameTour().trim() : null;

        String transportParam = (dto.getTransportation() != null && !dto.getTransportation().isBlank())
                ? dto.getTransportation().trim() : null;

        Integer startLocId = dto.getStartLocationID();
        Integer endLocId   = dto.getEndLocationID();

        // rating=0 nghĩa là "tất cả" -> null
        Integer minRating = (dto.getRating() != null && dto.getRating() > 0) ? dto.getRating() : null;

        BigDecimal minPrice = dto.getStartPrice();
        BigDecimal maxPrice = dto.getEndPrice();

        /*
         * JPQL strategy:
         * - (:param IS NULL OR condition) = nếu param null thì bỏ qua filter đó
         * - Subquery giá: chỉ tính giá ADULT trong departure active
         * - Subquery rating: AVG từ reviews (có thể không có review -> EXISTS check)
         */
        // salePrice trong JPQL tham chiếu entity field,
        // Hibernate dịch sang cột "price" theo @Column(name="price") trong DeparturePricing.
        String jpql = """
                SELECT DISTINCT t FROM Tour t
                LEFT JOIN FETCH t.endLocation
                LEFT JOIN FETCH t.startLocation
                LEFT JOIN FETCH t.departures
                WHERE t.status = true
                  AND (:nameParam IS NULL
                       OR LOWER(t.tourName) LIKE LOWER(CONCAT('%', CAST(:nameParam AS string), '%')))
                  AND (:startLocId IS NULL
                       OR t.startLocation.locationID = :startLocId)
                  AND (:endLocId IS NULL
                       OR t.endLocation.locationID = :endLocId)
                  AND (:transportParam IS NULL
                       OR t.transportation LIKE CONCAT('%', CAST(:transportParam AS string), '%'))
                  AND (:minRating IS NULL OR EXISTS (
                         SELECT r.tour.tourID FROM Review r
                         WHERE r.tour.tourID = t.tourID
                         GROUP BY r.tour.tourID
                         HAVING AVG(r.rating) >= :minRating
                       ))
                  AND t.tourID IN (
                         SELECT td.tour.tourID FROM TourDeparture td
                         JOIN td.pricings dp
                         WHERE dp.passengerType = 'ADULT'
                           AND td.status = true
                         GROUP BY td.tour.tourID
                         HAVING (:minPrice IS NULL OR MIN(dp.salePrice) >= :minPrice)
                            AND (:maxPrice IS NULL OR MIN(dp.salePrice) <= :maxPrice)
                       )
                """;

        return entityManager.createQuery(jpql, Tour.class)
                .setParameter("nameParam",     nameParam)
                .setParameter("startLocId",    startLocId)
                .setParameter("endLocId",      endLocId)
                .setParameter("transportParam", transportParam)
                .setParameter("minRating",     minRating)
                .setParameter("minPrice",      minPrice)
                .setParameter("maxPrice",      maxPrice)
                .getResultList();
    }
}
