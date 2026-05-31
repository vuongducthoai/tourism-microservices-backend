package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Integer> {

    Optional<Review> findByBookingId(Integer bookingId);

    boolean existsByBookingId(Integer bookingId);

    /**
     * Fetch visible reviews for a tour, ordered by createdAt DESC.
     * countQuery required because DISTINCT + JOIN FETCH + pagination needs a separate count.
     */
    @Query(value = "SELECT DISTINCT r FROM Review r " +
                   "LEFT JOIN FETCH r.images " +
                   "WHERE r.tour.tourCode = :tourCode AND r.isVisible = true",
           countQuery = "SELECT COUNT(r) FROM Review r " +
                        "WHERE r.tour.tourCode = :tourCode AND r.isVisible = true")
    Page<Review> findByTourCodeAndVisible(@Param("tourCode") String tourCode, Pageable pageable);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.tour.tourCode = :tourCode AND r.isVisible = true")
    Double getAverageRatingByTourCode(@Param("tourCode") String tourCode);

    @Query("SELECT COUNT(r) FROM Review r " +
           "WHERE r.tour.tourCode = :tourCode AND r.rating = :rating AND r.isVisible = true")
    Integer countByTourCodeAndRating(@Param("tourCode") String tourCode, @Param("rating") Integer rating);

    @Query("SELECT COUNT(r) FROM Review r " +
           "WHERE r.tour.tourCode = :tourCode AND r.isVisible = true")
    Integer countByTourCode(@Param("tourCode") String tourCode);

    /**
     * Lấy tất cả review visible kèm thông tin tour — dùng để sync lên chatbot Vector DB.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.tour t WHERE r.isVisible = true")
    List<Review> findAllVisible();

    /** Dashboard: average rating toàn bộ hệ thống */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.isVisible = true")
    Double calculateAverageRating();

    /** Lấy tối đa N review mới nhất (visible, có comment đủ dài) để feed cho AI summary. */
    @Query("SELECT r FROM Review r " +
           "WHERE r.tour.tourID = :tourId " +
           "  AND r.isVisible = true " +
           "  AND r.comment IS NOT NULL AND LENGTH(r.comment) >= 20 " +
           "ORDER BY r.createdAt DESC")
    List<Review> findTopReviewsForSummary(@Param("tourId") Integer tourId,
                                          org.springframework.data.domain.Pageable pageable);

    /** Đếm review hợp lệ (visible) của 1 tour. */
    @Query("SELECT COUNT(r) FROM Review r WHERE r.tour.tourID = :tourId AND r.isVisible = true")
    long countVisibleByTourId(@Param("tourId") Integer tourId);
}
